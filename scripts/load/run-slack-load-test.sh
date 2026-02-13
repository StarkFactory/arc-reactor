#!/usr/bin/env bash
set -euo pipefail

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is required. Install: https://k6.io/docs/get-started/installation/"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
MODE="${MODE:-mixed}"
VUS="${VUS:-30}"
DURATION="${DURATION:-2m}"
SLACK_SIGNING_SECRET="${SLACK_SIGNING_SECRET:-}"

echo "Running Slack load test"
echo "  BASE_URL=$BASE_URL"
echo "  MODE=$MODE"
echo "  VUS=$VUS"
echo "  DURATION=$DURATION"

BASE_URL="$BASE_URL" \
MODE="$MODE" \
VUS="$VUS" \
DURATION="$DURATION" \
SLACK_SIGNING_SECRET="$SLACK_SIGNING_SECRET" \
k6 run scripts/load/slack-gateway-load.js
