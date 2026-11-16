#!/usr/bin/env bash
# Deploys one specific image to a Render service and waits until it's live (OPS-03).
#
#   scripts/render-deploy.sh <deploy-hook-url> <image>
#
# <image> must be in the service's registry and repository, e.g.
# ghcr.io/owner/linguaflow-backend@sha256:…; a digest pins exactly what was tested.
#
# With RENDER_API_KEY set, the deploy is followed through Render's API until it's live
# (exit 0) or failed (exit 1), at most DEPLOY_TIMEOUT_SECONDS (default 900). Without it,
# the deploy is only triggered. RENDER_API_BASE overrides https://api.render.com (tests).
# The hook URL is a secret: it's never printed.
set -euo pipefail

if [ $# -ne 2 ] || [ -z "$1" ] || [ -z "$2" ]; then
  echo "usage: $0 <deploy-hook-url> <image>" >&2
  exit 2
fi
hook=$1
image=$2
timeout=${DEPLOY_TIMEOUT_SECONDS:-900}
api=${RENDER_API_BASE:-https://api.render.com}

encoded_image=$(jq -rn --arg value "$image" '$value | @uri')
case "$hook" in
  *\?*) url="${hook}&imgURL=${encoded_image}" ;;
  *) url="${hook}?imgURL=${encoded_image}" ;;
esac

status_code=$(curl -sS -o /tmp/render-deploy-response.json -w '%{http_code}' -X POST "$url")
case "$status_code" in
  200) ;;
  202)
    echo "::notice::Render queued the deploy of ${image} behind one in progress (no deploy ID to follow)"
    exit 0
    ;;
  *)
    echo "::error::Render refused the deploy of ${image}: HTTP ${status_code}" >&2
    exit 1
    ;;
esac
deploy_id=$(jq -r '.deploy.id // .id // empty' /tmp/render-deploy-response.json)
echo "Render started deploy ${deploy_id:-?} of ${image}"

if [ -z "${RENDER_API_KEY:-}" ]; then
  echo "::notice::RENDER_API_KEY isn't set, so the deploy isn't followed; check it in the Render Dashboard"
  exit 0
fi
if [ -z "$deploy_id" ]; then
  echo "::warning::Render's answer had no deploy ID to follow"
  exit 0
fi
# The hook URL is …/deploy/srv-<id>?key=…
service_id=$(sed -E 's#^.*/deploy/(srv-[A-Za-z0-9]+).*$#\1#' <<<"$hook")

deadline=$(( $(date +%s) + timeout ))
while :; do
  deploy_status=$(curl -fsS -H "Authorization: Bearer ${RENDER_API_KEY}" -H 'Accept: application/json' \
    "${api}/v1/services/${service_id}/deploys/${deploy_id}" | jq -r '.status // .deploy.status // "unknown"')
  case "$deploy_status" in
    live)
      echo "Deploy ${deploy_id} is live"
      exit 0
      ;;
    build_failed | update_failed | pre_deploy_failed | canceled | deactivated)
      echo "::error::Deploy ${deploy_id} of ${image} ended as ${deploy_status}" >&2
      exit 1
      ;;
  esac
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "::error::Deploy ${deploy_id} still ${deploy_status} after ${timeout}s" >&2
    exit 1
  fi
  echo "Deploy ${deploy_id}: ${deploy_status}"
  sleep "${DEPLOY_POLL_SECONDS:-10}"
done
