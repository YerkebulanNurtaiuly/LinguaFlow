#!/usr/bin/env bash
# Logical backup of a LinguaFlow database (OPS-05).
#
#   SOURCE_DATABASE_URL=postgresql://user:pass@host:port/db scripts/db-backup.sh [file]
#
# Writes a pg_dump custom-format archive, by default backups/linguaflow-<UTC time>.dump,
# readable only by you. The archive holds every learner's personal data: keep it encrypted,
# outside the repository (*.dump is git-ignored) and delete it when it is no longer needed.
# See docs/operations/backup-restore.md.
set -euo pipefail

: "${SOURCE_DATABASE_URL:?set SOURCE_DATABASE_URL to the postgresql:// URL of the database to back up}"
export SOURCE_DATABASE_URL
# shellcheck source=lib/pg.sh
source "$(dirname "$0")/lib/pg.sh"

out=${1:-backups/linguaflow-$(date -u +%Y%m%dT%H%M%SZ).dump}
mkdir -p "$(dirname "$out")"
umask 077
started=$(date +%s)

# Owners and grants are left out: the restore target has its own user
# shellcheck disable=SC2016
pg_run 'pg_dump --format=custom --compress=6 --no-owner --no-acl --dbname="$SOURCE_DATABASE_URL"' > "$out.partial"

# An archive that pg_restore can't read is no backup
entries=$(pg_run 'pg_restore --list' < "$out.partial" | grep -c ' TABLE DATA ' || true)
if [ "$entries" -eq 0 ]; then
  rm -f "$out.partial"
  echo "Backup failed: the archive has no table data" >&2
  exit 1
fi
mv "$out.partial" "$out"

size=$(wc -c < "$out" | tr -d ' ')
sum=$(sha256sum "$out" | cut -d' ' -f1)
echo "Backup written: $out ($size bytes, $entries tables, $(( $(date +%s) - started )) s)"
echo "sha256: $sum"
