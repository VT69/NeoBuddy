#!/usr/bin/env bash
# Renders every parameter under the SSM path (default /neobuddy/prod/) into an env file for the
# app, readable by root only. The parameter's last path segment is the variable name:
#   /neobuddy/prod/NEOBUDDY_ENCRYPTION_KEY  ->  NEOBUDDY_ENCRYPTION_KEY='...'
#
# Usage: render-env.sh [output-file]        (default /etc/neobuddy/app.env)
#
# Values are written single-quoted, which docker compose takes literally (no ${...} expansion).
# A value containing a single quote or a line break is refused rather than mangled. Parameter
# names are printed; values never are. The old file is replaced only once the new one is complete.
set -euo pipefail

out="${1:-${NEOBUDDY_ETC:-/etc/neobuddy}/app.env}"
path="${SSM_PATH:-/neobuddy/prod/}"
region="${AWS_REGION:?AWS_REGION must be set}"
required=(DB_PASSWORD NEOBUDDY_ENCRYPTION_KEY NEOBUDDY_DOMAIN NEOBUDDY_SES_FROM)

umask 077
out_dir="$(dirname "$out")"
mkdir -p "$out_dir"
tmp="$(mktemp "$out_dir/.app.env.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

# One line per parameter: name, a tab, the value in base64 (so any character survives the shell).
# The CLI follows NextToken itself, so this is every parameter under the path.
lines="$(aws ssm get-parameters-by-path \
  --region "$region" \
  --path "$path" \
  --with-decryption \
  --output json |
  jq -r '.Parameters[] | "\(.Name)\t\(.Value | @base64)"')"

names=()
while IFS=$'\t' read -r full encoded; do
  [[ -z "$full" ]] && continue
  name="${full##*/}"
  if [[ ! "$name" =~ ^[A-Z_][A-Z0-9_]*$ ]]; then
    echo "render-env: parameter $full: name must be an environment variable name" >&2
    exit 1
  fi
  value="$(printf '%s' "$encoded" | base64 -d; printf x)"
  value="${value%x}"
  if [[ "$value" == *$'\n'* || "$value" == *$'\r'* || "$value" == *"'"* ]]; then
    echo "render-env: parameter $full: value must not contain a line break or a single quote" >&2
    exit 1
  fi
  printf "%s='%s'\n" "$name" "$value" >>"$tmp"
  names+=("$name")
done <<<"$lines"

missing=()
for want in "${required[@]}"; do
  found=false
  for name in "${names[@]}"; do
    [[ "$name" == "$want" ]] && found=true && break
  done
  $found || missing+=("$want")
done
if ((${#missing[@]} > 0)); then
  echo "render-env: missing under $path: ${missing[*]}" >&2
  exit 1
fi

chmod 600 "$tmp"
mv -f "$tmp" "$out"
trap - EXIT
echo "render-env: wrote ${#names[@]} variables to $out: ${names[*]}"
