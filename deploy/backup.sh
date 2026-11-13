#!/usr/bin/env bash
# Nightly backup, run by neobuddy-backup.timer: pg_dump | gzip to the S3 backup bucket.
#
# The dump is written to local disk and checked before it is uploaded, so a dump that failed
# half way never lands in the bucket looking like a good one. The instance may only put objects;
# it cannot read or delete backups (see deploy/README.md, "Restore from a backup").
set -euo pipefail

home="${NEOBUDDY_HOME:-/opt/neobuddy}"
etc="${NEOBUDDY_ETC:-/etc/neobuddy}"
# shellcheck source=/dev/null
. "$etc/infra.env"
: "${BACKUP_BUCKET:?BACKUP_BUCKET missing from infra.env}" "${AWS_REGION:?}"

work="$(mktemp -d "${TMPDIR:-/var/tmp}/neobuddy-backup.XXXXXX")"
trap 'rm -rf "$work"' EXIT
dump="$work/neobuddy.sql.gz"
key="daily/neobuddy-$(date -u +%Y-%m-%dT%H%M%SZ).sql.gz"

"$home/compose.sh" exec -T db pg_dump --username neobuddy --dbname neobuddy --no-owner |
  gzip -9 >"$dump"
gzip -t "$dump"

aws s3 cp "$dump" "s3://$BACKUP_BUCKET/$key" --region "$AWS_REGION" --only-show-errors
echo "backup: uploaded s3://$BACKUP_BUCKET/$key ($(stat -c %s "$dump") bytes)"
