#!/usr/bin/env bash
# Runs deploy/deploy.sh on the instance through SSM Run Command and waits for it to finish.
#
#   ssm-deploy.sh <instance-id> <image>
#
# The deploy/ directory of the checked-out commit travels inside the command as a base64 tarball,
# so the instance needs no access to the repository and always runs the scripts of the commit it
# deploys. Exits non-zero unless the remote script succeeded.
set -euo pipefail

instance="${1:?usage: ssm-deploy.sh <instance-id> <image>}"
image="${2:?usage: ssm-deploy.sh <instance-id> <image>}"
timeout_seconds="${DEPLOY_TIMEOUT_SECONDS:-900}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

bundle="$(tar -czf - -C "$root/deploy" --exclude README.md --owner=0 --group=0 . | base64 -w0)"
echo "Deploy bundle: ${#bundle} bytes of base64"

params="$(mktemp)"
trap 'rm -f "$params"' EXIT
jq -n --arg bundle "$bundle" --arg image "$image" --arg timeout "$timeout_seconds" '{
  commands: [
    "set -e",
    "rm -rf /opt/neobuddy/incoming && mkdir -p /opt/neobuddy/incoming",
    ("echo " + $bundle + " | base64 -d | tar -xzf - -C /opt/neobuddy/incoming"),
    ("/opt/neobuddy/incoming/deploy.sh " + $image)
  ],
  executionTimeout: [$timeout]
}' >"$params"

command_id="$(aws ssm send-command \
  --instance-ids "$instance" \
  --document-name AWS-RunShellScript \
  --comment "NeoBuddy deploy ${image##*:}" \
  --timeout-seconds 600 \
  --parameters "file://$params" \
  --query Command.CommandId --output text)"
echo "SSM command $command_id sent to $instance"

deadline=$((SECONDS + timeout_seconds + 60))
status=Pending
while true; do
  status="$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" \
    --query Status --output text 2>/dev/null || echo Pending)"
  case "$status" in
    Success | Failed | Cancelled | TimedOut | Cancelling) break ;;
  esac
  if ((SECONDS >= deadline)); then
    echo "Gave up waiting for the deploy command (last status: $status)" >&2
    exit 1
  fi
  sleep 5
done

echo "----- instance output -----"
aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" \
  --query StandardOutputContent --output text
echo "----- instance errors -----"
aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" \
  --query StandardErrorContent --output text
echo "---------------------------"
echo "Deploy command finished: $status"
[[ "$status" == Success ]]
