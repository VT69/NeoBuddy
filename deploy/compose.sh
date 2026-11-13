#!/usr/bin/env bash
# docker compose for the production stack, with its env files. Any compose command works:
#   /opt/neobuddy/compose.sh ps
#   /opt/neobuddy/compose.sh logs --tail 100 app
set -euo pipefail

home="${NEOBUDDY_HOME:-/opt/neobuddy}"
etc="${NEOBUDDY_ETC:-/etc/neobuddy}"
export NEOBUDDY_ETC="$etc"

exec docker compose \
  --project-name neobuddy \
  --project-directory "$home" \
  --file "$home/docker-compose.prod.yml" \
  --env-file "$etc/infra.env" \
  --env-file "$etc/app.env" \
  --env-file "$etc/image.env" \
  "$@"
