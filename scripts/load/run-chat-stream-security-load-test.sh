#!/usr/bin/env bash
set -euo pipefail

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is required. Install: https://k6.io/docs/get-started/installation/"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
STREAM_PATH="${STREAM_PATH:-/api/chat/stream}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
AUTH_TOKENS="${AUTH_TOKENS:-}"
TENANT_ID="${TENANT_ID:-default}"
OTHER_TENANT_ID="${OTHER_TENANT_ID:-other-tenant}"
MODE="${MODE:-mixed}"
OVERSIZED_CHARS="${OVERSIZED_CHARS:-15000}"
VUS="${VUS:-1}"
DURATION="${DURATION:-1m}"
SLEEP_SECONDS="${SLEEP_SECONDS:-6.5}"
SUMMARY_EXPORT="${SUMMARY_EXPORT:-}"
token_list="${AUTH_TOKENS:-$AUTH_TOKEN}"
token_count="$(printf '%s\n' "$token_list" | tr '|,' '\n' | sed '/^$/d' | wc -l | tr -d ' ')"

if [[ -z "$token_list" ]]; then
  echo "AUTH_TOKEN or AUTH_TOKENS is required."
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
  TOKEN_COUNT=$token_count
  SLEEP_SECONDS=$SLEEP_SECONDS
  TENANT_ID=$TENANT_ID
  OTHER_TENANT_ID=$OTHER_TENANT_ID
  SUMMARY_EXPORT=${SUMMARY_EXPORT:-<none>}
INFO

if [[ "$token_count" -le 1 && "$SLEEP_SECONDS" == "6.5" ]]; then
  echo "  Note: default pacing is rate-limit-safe for a single token. Use AUTH_TOKENS for higher-concurrency load."
fi

if [[ ${#k6_args[@]} -gt 0 ]]; then
  BASE_URL="$BASE_URL" \
  STREAM_PATH="$STREAM_PATH" \
  AUTH_TOKEN="$AUTH_TOKEN" \
  AUTH_TOKENS="$AUTH_TOKENS" \
  TENANT_ID="$TENANT_ID" \
  OTHER_TENANT_ID="$OTHER_TENANT_ID" \
  MODE="$MODE" \
  OVERSIZED_CHARS="$OVERSIZED_CHARS" \
  VUS="$VUS" \
  DURATION="$DURATION" \
  SLEEP_SECONDS="$SLEEP_SECONDS" \
  k6 run "${k6_args[@]}" scripts/load/chat-stream-security-load.js
else
  BASE_URL="$BASE_URL" \
  STREAM_PATH="$STREAM_PATH" \
  AUTH_TOKEN="$AUTH_TOKEN" \
  AUTH_TOKENS="$AUTH_TOKENS" \
  TENANT_ID="$TENANT_ID" \
  OTHER_TENANT_ID="$OTHER_TENANT_ID" \
  MODE="$MODE" \
  OVERSIZED_CHARS="$OVERSIZED_CHARS" \
  VUS="$VUS" \
  DURATION="$DURATION" \
  SLEEP_SECONDS="$SLEEP_SECONDS" \
  k6 run scripts/load/chat-stream-security-load.js
fi
