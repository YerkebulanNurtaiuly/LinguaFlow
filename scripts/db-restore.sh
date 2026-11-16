#!/usr/bin/env bash
# Restores a backup made by db-backup.sh (or a Render logical export) into an EMPTY
# database (OPS-05).
#
#   TARGET_DATABASE_URL=postgresql://user:pass@host:port/db scripts/db-restore.sh <file>
#
# It refuses a target that already has tables, so it can never overwrite a live database:
# restore into a new database, check it, then point the backend at it. The restore runs in
# one transaction and stops at the first error, so a failed restore leaves the target empty.
# See docs/operations/backup-restore.md.
set -euo pipefail

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: TARGET_DATABASE_URL=... $0 <backup file>" >&2
  exit 2
fi
: "${TARGET_DATABASE_URL:?set TARGET_DATABASE_URL to the postgresql:// URL of the empty target database}"
export TARGET_DATABASE_URL
# shellcheck source=lib/pg.sh
source "$(dirname "$0")/lib/pg.sh"

tables=$(pg_query TARGET_DATABASE_URL <<<"select count(*) from pg_tables where schemaname = 'public';" | tr -d '[:space:]')
if [ "$tables" != "0" ]; then
  echo "The target database already has $tables tables; restore into a new, empty database" >&2
  exit 1
fi

started=$(date +%s)
# shellcheck disable=SC2016
pg_run 'pg_restore --no-owner --no-acl --exit-on-error --single-transaction --dbname="$TARGET_DATABASE_URL"' < "$1"

version=$(pg_query TARGET_DATABASE_URL <<<"select max(version::int) from flyway_schema_history where success and version is not null;" | tr -d '[:space:]')
echo "Restored $1 in $(( $(date +%s) - started )) s; schema at Flyway version ${version:-unknown}"
