#!/usr/bin/env bash
set -euo pipefail

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is required. Install: https://k6.io/docs/get-started/installation/"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
STREAM_PATH="${STREAM_PATH:-/api/chat/stream}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
TENANT_ID="${TENANT_ID:-default}"
OTHER_TENANT_ID="${OTHER_TENANT_ID:-other-tenant}"
MODE="${MODE:-mixed}"
OVERSIZED_CHARS="${OVERSIZED_CHARS:-15000}"
VUS="${VUS:-5}"
DURATION="${DURATION:-1m}"
SLEEP_SECONDS="${SLEEP_SECONDS:-0.1}"
SUMMARY_EXPORT="${SUMMARY_EXPORT:-}"

if [[ -z "$AUTH_TOKEN" ]]; then
  echo "AUTH_TOKEN is required."
  echo "Example:"
  echo "  AUTH_TOKEN=\"<jwt>\" ./scripts/load/run-chat-stream-security-load-test.sh"
  exit 1
fi

k6_args=()
if [[ -n "$SUMMARY_EXPORT" ]]; then
  mkdir -p "$(dirname "$SUMMARY_EXPORT")"
  k6_args+=(--summary-export "$SUMMARY_EXPORT")
fi

cat <<INFO
Running chat stream security load test
  BASE_URL=$BASE_URL
  STREAM_PATH=$STREAM_PATH
  MODE=$MODE
  VUS=$VUS
  DURATION=$DURATION
  TENANT_ID=$TENANT_ID
  OTHER_TENANT_ID=$OTHER_TENANT_ID
  SUMMARY_EXPORT=${SUMMARY_EXPORT:-<none>}
INFO

BASE_URL="$BASE_URL" \
STREAM_PATH="$STREAM_PATH" \
AUTH_TOKEN="$AUTH_TOKEN" \
TENANT_ID="$TENANT_ID" \
OTHER_TENANT_ID="$OTHER_TENANT_ID" \
MODE="$MODE" \
OVERSIZED_CHARS="$OVERSIZED_CHARS" \
VUS="$VUS" \
DURATION="$DURATION" \
SLEEP_SECONDS="$SLEEP_SECONDS" \
k6 run "${k6_args[@]}" scripts/load/chat-stream-security-load.js
