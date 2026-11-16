#!/usr/bin/env bash
# Restore drill (OPS-05): proves that a backup restores into a working LinguaFlow.
#
#   scripts/restore-drill.sh
#
# 1. Starts a throwaway stack (compose project linguaflow-drill: PostgreSQL and the backend
#    with the demo data and scripted AI), signs in as the demo learner and adds a marker word.
# 2. Stops the backend, as if the database were all that's left, and backs it up with
#    db-backup.sh.
# 3. Starts a second, empty PostgreSQL server and restores the backup into it with
#    db-restore.sh, then compares every table with db-verify.sh.
# 4. Starts a backend on the restored database and checks through the API that the demo
#    learner can sign in and still has every word, the marker included.
#
# Prints the time each step took. Everything, the backup file included, is removed at the
# end; KEEP_DRILL=1 keeps the containers for a look. Ports: DRILL_DB_PORT (5436),
# DRILL_BACKEND_PORT (8086), DRILL_RESTORED_PORT (8087). Needs Docker and curl.
set -euo pipefail
cd "$(dirname "$0")/.."

project=linguaflow-drill
network=${project}_default
restore_db=${project}-restore-db
restored_api=${project}-restored-api
backend_port=${DRILL_BACKEND_PORT:-8086}
restored_port=${DRILL_RESTORED_PORT:-8087}
workdir=$(mktemp -d)
backup=$workdir/drill.dump
demo_email=demo@linguaflow.local
demo_password=demo-password-123 # the public dev-seed account
compose=(docker compose -p "$project" -f docker-compose.yml -f docker-compose.e2e.yml)
export DB_PORT=${DRILL_DB_PORT:-5436} BACKEND_PORT=$backend_port

cleanup() {
  status=$?
  if [ "${KEEP_DRILL:-0}" = 1 ]; then
    echo "Kept: compose project $project, containers $restore_db and $restored_api"
  else
    docker rm -f "$restored_api" "$restore_db" >/dev/null 2>&1 || true
    "${compose[@]}" down -v >/dev/null 2>&1 || true
  fi
  rm -rf "$workdir"
  [ $status -eq 0 ] && echo "Restore drill passed" || echo "Restore drill FAILED" >&2
}
trap cleanup EXIT

step_started=$(date +%s)
step() {
  local now
  now=$(date +%s)
  [ -n "${step_name:-}" ] && printf '   %s took %d s\n' "$step_name" $(( now - step_started ))
  step_name=$1
  step_started=$now
  printf '\n== %s\n' "$1"
}

json_string() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed 's/.*:"\(.*\)"/\1/'; }
json_number() { grep -o "\"$1\":[0-9]*" | head -1 | sed 's/.*://'; }

wait_healthy() {
  local url=$1 deadline=$(( $(date +%s) + 240 ))
  until curl -fsS --max-time 5 "$url/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "$url didn't become healthy" >&2
      return 1
    fi
    sleep 3
  done
}

login() {
  curl -fsS "$1/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$demo_email\",\"password\":\"$demo_password\"}" | json_string accessToken
}

vocabulary_total() { # base-url token [query]
  curl -fsS -G "$1/api/vocabulary" -H "Authorization: Bearer $2" --data-urlencode "q=${3:-}" \
    --data-urlencode size=1 | json_number totalElements
}

step "Start the source stack"
"${compose[@]}" up -d --build --wait db backend
wait_healthy "http://localhost:$backend_port"

step "Add a marker word as the demo learner"
token=$(login "http://localhost:$backend_port")
marker="restore drill $(date -u +%Y%m%d%H%M%S)"
curl -fsS "http://localhost:$backend_port/api/vocabulary" -H "Authorization: Bearer $token" \
  -H 'Content-Type: application/json' -d "{\"lemma\":\"Probe\",\"translation\":\"$marker\"}" >/dev/null
words_before=$(vocabulary_total "http://localhost:$backend_port" "$token")
echo "The demo learner has $words_before words, one translated \"$marker\""
# Let the AI worker finish enriching it, so the database is quiet before the backup
sleep 5

step "Stop the backend and back up the database"
"${compose[@]}" stop backend
SOURCE_DATABASE_URL=postgresql://linguaflow:linguaflow@db:5432/linguaflow PG_NETWORK=$network \
  scripts/db-backup.sh "$backup"

step "Restore into a new, empty PostgreSQL server"
docker run -d --name "$restore_db" --network "$network" \
  -e POSTGRES_DB=linguaflow -e POSTGRES_USER=linguaflow -e POSTGRES_PASSWORD=linguaflow \
  postgres:16-alpine >/dev/null
until docker exec "$restore_db" pg_isready -q -U linguaflow -d linguaflow; do sleep 1; done
sleep 2 # the image restarts the server once after initialising it
until docker exec "$restore_db" pg_isready -q -U linguaflow -d linguaflow; do sleep 1; done
restore_started=$(date +%s)
TARGET_DATABASE_URL=postgresql://linguaflow:linguaflow@$restore_db:5432/linguaflow PG_NETWORK=$network \
  scripts/db-restore.sh "$backup"

step "Compare every table"
SOURCE_DATABASE_URL=postgresql://linguaflow:linguaflow@db:5432/linguaflow \
TARGET_DATABASE_URL=postgresql://linguaflow:linguaflow@$restore_db:5432/linguaflow \
PG_NETWORK=$network scripts/db-verify.sh

step "Start a backend on the restored database"
# The dev profile, like the source: the restored schema includes the dev seed's history
docker run -d --name "$restored_api" --network "$network" -p "127.0.0.1:$restored_port:8080" \
  -e SPRING_PROFILES_ACTIVE=dev -e AI_PROVIDER=scripted -e LINGUAFLOW_RATE_LIMIT_ENABLED=false \
  -e "DB_URL=jdbc:postgresql://$restore_db:5432/linguaflow" -e DB_USERNAME=linguaflow -e DB_PASSWORD=linguaflow \
  "${project}-backend" >/dev/null
wait_healthy "http://localhost:$restored_port"
restored_in=$(( $(date +%s) - restore_started ))

step "Check the learner's data through the API"
token=$(login "http://localhost:$restored_port")
words_after=$(vocabulary_total "http://localhost:$restored_port" "$token")
found=$(vocabulary_total "http://localhost:$restored_port" "$token" "$marker")
echo "The demo learner has $words_after words after the restore (before: $words_before); marker found: $found"
if [ "$words_after" != "$words_before" ] || [ "$found" != 1 ]; then
  echo "The restored app doesn't serve the backed-up data" >&2
  exit 1
fi

step "Done"
echo "From an empty server to a healthy app on the restored data: $restored_in s"
