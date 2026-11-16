# shellcheck shell=bash
# Shared helper for the database scripts (OPS-05); sourced, not run.
#
# pg_run '<shell command>' runs a PostgreSQL client command. It uses the local client
# tools when they are installed and PG_NETWORK is unset, otherwise the postgres image
# (PG_IMAGE, default postgres:16-alpine) on the Docker network PG_NETWORK (default host).
#
# Database URLs are passed as the environment variables SOURCE_DATABASE_URL and
# TARGET_DATABASE_URL and expanded inside the command, never as arguments, so passwords
# stay out of process listings and shell history. Write the command in single quotes.
# stdin and stdout pass through, so dumps can be streamed in and out.

pg_run() {
  if [ -z "${PG_NETWORK:-}" ] && command -v pg_dump >/dev/null 2>&1; then
    sh -c "$1"
  else
    docker run --rm -i --network "${PG_NETWORK:-host}" \
      -e SOURCE_DATABASE_URL -e TARGET_DATABASE_URL \
      "${PG_IMAGE:-postgres:16-alpine}" sh -c "$1"
  fi
}

# Runs the SQL on stdin against the database whose URL is in the variable named $1
# (SOURCE_DATABASE_URL or TARGET_DATABASE_URL) and prints the rows unaligned, one per line,
# fields separated by a space.
pg_query() {
  case $1 in
    SOURCE_DATABASE_URL | TARGET_DATABASE_URL) ;;
    *) echo "pg_query: unknown URL variable $1" >&2; return 2 ;;
  esac
  pg_run 'psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 --tuples-only --no-align --field-separator=" " --dbname="$'"$1"'"'
}

# Row counts of every table in the public schema, as "table count" lines sorted bytewise.
pg_row_counts() {
  pg_query "$1" <<'SQL' | LC_ALL=C sort
select table_name,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from public.%I', table_name), false, true, '')))[1]::text
  from information_schema.tables
 where table_schema = 'public' and table_type = 'BASE TABLE';
SQL
}
