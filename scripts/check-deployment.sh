#!/usr/bin/env bash
# Checks that an environment serves a given commit and is healthy (OPS-03).
#
#   scripts/check-deployment.sh <base-url> <commit>
#
# Waits (at most CHECK_TIMEOUT_SECONDS, default 600) until <base-url>/version.txt names the
# commit, which the frontend image records, and <base-url>/actuator/health reports UP
# through nginx, which needs the backend and its database.
set -euo pipefail

if [ $# -ne 2 ] || [ -z "$1" ] || [ -z "$2" ]; then
  echo "usage: $0 <base-url> <commit>" >&2
  exit 2
fi
base=${1%/}
commit=$2
timeout=${CHECK_TIMEOUT_SECONDS:-600}
deadline=$(( $(date +%s) + timeout ))

served=""
health=""
while :; do
  served=$(curl -fsS --max-time 10 "${base}/version.txt" 2>/dev/null | tr -d '[:space:]' || true)
  health=$(curl -fsS --max-time 10 "${base}/actuator/health" 2>/dev/null | jq -r '.status // empty' 2>/dev/null || true)
  if [ "$served" = "$commit" ] && [ "$health" = "UP" ]; then
    echo "${base} serves ${commit} and is UP"
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "::error::${base} serves '${served:-nothing}' with health '${health:-unreachable}', expected ${commit} and UP" >&2
    exit 1
  fi
  sleep "${CHECK_POLL_SECONDS:-10}"
done
