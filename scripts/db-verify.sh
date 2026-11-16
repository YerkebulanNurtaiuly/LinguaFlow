#!/usr/bin/env bash
# Compares two LinguaFlow databases table by table (OPS-05): after a restore, the restored
# copy must have exactly the rows of the database the backup was taken from.
#
#   SOURCE_DATABASE_URL=... TARGET_DATABASE_URL=... scripts/db-verify.sh
#
# Only meaningful while the source isn't changing (or against the moment the backup was
# taken). Prints every table with both counts and exits 1 on any difference.
set -euo pipefail

: "${SOURCE_DATABASE_URL:?set SOURCE_DATABASE_URL}"
: "${TARGET_DATABASE_URL:?set TARGET_DATABASE_URL}"
export SOURCE_DATABASE_URL TARGET_DATABASE_URL
# shellcheck source=lib/pg.sh
source "$(dirname "$0")/lib/pg.sh"

source_counts=$(pg_row_counts SOURCE_DATABASE_URL)
target_counts=$(pg_row_counts TARGET_DATABASE_URL)
if [ -z "$source_counts" ]; then
  echo "The source database has no tables" >&2
  exit 1
fi

LC_ALL=C join -a1 -a2 -e missing -o 0,1.2,2.2 <(echo "$source_counts") <(echo "$target_counts") |
  awk 'BEGIN { printf "%-32s %10s %10s\n", "table", "source", "restored"; bad = 0; rows = 0 }
       { flag = ($2 == $3) ? "" : "  <-- differs"; if (flag != "") bad++; rows += $2
         printf "%-32s %10s %10s%s\n", $1, $2, $3, flag }
       END { printf "%d tables, %d rows, %d differences\n", NR, rows, bad; exit bad > 0 }'
