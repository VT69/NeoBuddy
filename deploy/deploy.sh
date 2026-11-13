#!/usr/bin/env bash
# Deploys one image on the instance. The deploy workflow unpacks the deploy/ directory of the
# commit being deployed into a fresh directory and runs this script from it:
#
#   deploy.sh <ecr-repository-url>:<git-sha>
#
# It installs this release's compose file, Caddyfile, scripts and backup timer into
# /opt/neobuddy, renders the env file from SSM, pulls the image, restarts what changed and waits
# until the app reports UP. A rollback is the same call with an older tag.
set -euo pipefail

image="${1:?usage: deploy.sh <repository-url>:<tag>}"
if [[ ! "$image" =~ ^[a-z0-9.-]+/[a-z0-9._/-]+:[A-Za-z0-9._-]{1,128}$ ]]; then
  echo "deploy: not an image reference: $image" >&2
  exit 2
fi

src="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
home="${NEOBUDDY_HOME:-/opt/neobuddy}"
etc="${NEOBUDDY_ETC:-/etc/neobuddy}"
health_timeout="${HEALTH_TIMEOUT_SECONDS:-300}"

# shellcheck source=/dev/null
. "$etc/infra.env"
: "${AWS_REGION:?}" "${ECR_REGISTRY:?}" "${LOG_GROUP:?}"

# A rollback can also be run from the installed copy (/opt/neobuddy/deploy.sh <older-tag>); then
# the files are already in place.
if [[ "$src" != "$(cd "$home" 2>/dev/null && pwd)" ]]; then
  echo "deploy: installing release files into $home"
  install -d -m 755 "$home" "$home/systemd"
  for file in docker-compose.prod.yml Caddyfile; do
    install -m 644 "$src/$file" "$home/$file"
  done
  for file in compose.sh render-env.sh backup.sh deploy.sh; do
    install -m 755 "$src/$file" "$home/$file"
  done
  for unit in neobuddy-backup.service neobuddy-backup.timer; do
    install -m 644 "$src/systemd/$unit" "$home/systemd/$unit"
  done
fi
for unit in neobuddy-backup.service neobuddy-backup.timer; do
  install -m 644 "$home/systemd/$unit" "/etc/systemd/system/$unit"
done
systemctl daemon-reload
systemctl enable --now neobuddy-backup.timer >/dev/null

echo "deploy: rendering $etc/app.env from SSM"
AWS_REGION="$AWS_REGION" SSM_PATH="${SSM_PATH:-/neobuddy/prod/}" "$home/render-env.sh" "$etc/app.env"

echo "deploy: logging in to $ECR_REGISTRY"
aws ecr get-login-password --region "$AWS_REGION" |
  docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null

if [[ -f "$etc/image.env" ]]; then
  cp -f "$etc/image.env" "$etc/image.env.previous"
fi
printf 'NEOBUDDY_IMAGE=%s\n' "$image" >"$etc/image.env.new"
mv -f "$etc/image.env.new" "$etc/image.env"

echo "deploy: starting $image"
"$home/compose.sh" pull --quiet
"$home/compose.sh" up -d --remove-orphans

echo "deploy: waiting up to ${health_timeout}s for the app to report UP"
deadline=$((SECONDS + health_timeout))
until curl -fsS -m 5 http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
  if ((SECONDS >= deadline)); then
    echo "deploy: the app did not report UP within ${health_timeout}s" >&2
    "$home/compose.sh" ps >&2
    echo "deploy: its logs are in CloudWatch, log group $LOG_GROUP" >&2
    exit 1
  fi
  sleep 5
done

# Free the disk of images no container uses. A rollback pulls its image from ECR again.
docker image prune --all --force >/dev/null || true
echo "deploy: $image is UP"
