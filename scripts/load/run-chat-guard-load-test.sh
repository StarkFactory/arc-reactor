#!/usr/bin/env bash
set -euo pipefail

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is required. Install: https://k6.io/docs/get-started/installation/"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
CHAT_PATH="${CHAT_PATH:-/api/chat}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
TENANT_ID="${TENANT_ID:-default}"
OTHER_TENANT_ID="${OTHER_TENANT_ID:-other-tenant}"
MODE="${MODE:-mixed}"
OVERSIZED_CHARS="${OVERSIZED_CHARS:-15000}"
VUS="${VUS:-5}"
DURATION="${DURATION:-1m}"
SLEEP_SECONDS="${SLEEP_SECONDS:-0.1}"

if [[ -z "$AUTH_TOKEN" ]]; then
  echo "AUTH_TOKEN is required."
  echo "Example:"
  echo "  AUTH_TOKEN=\"<jwt>\" ./scripts/load/run-chat-guard-load-test.sh"
  exit 1
fi

cat <<INFO
Running chat guard/filtering load test
  BASE_URL=$BASE_URL
  CHAT_PATH=$CHAT_PATH
  MODE=$MODE
  VUS=$VUS
  DURATION=$DURATION
  TENANT_ID=$TENANT_ID
  OTHER_TENANT_ID=$OTHER_TENANT_ID
INFO

BASE_URL="$BASE_URL" \
CHAT_PATH="$CHAT_PATH" \
AUTH_TOKEN="$AUTH_TOKEN" \
TENANT_ID="$TENANT_ID" \
OTHER_TENANT_ID="$OTHER_TENANT_ID" \
MODE="$MODE" \
OVERSIZED_CHARS="$OVERSIZED_CHARS" \
VUS="$VUS" \
DURATION="$DURATION" \
SLEEP_SECONDS="$SLEEP_SECONDS" \
k6 run scripts/load/chat-guard-load.js
