#!/usr/bin/env bash
set -euo pipefail

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is required. Install: https://k6.io/docs/get-started/installation/"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
VUS="${VUS:-1}"
DURATION="${DURATION:-1m}"
CHAT_PATH="${CHAT_PATH:-/api/chat}"
PROMPTS="${PROMPTS:-What is 2 + 2?|Summarize policy controls for write tools}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
AUTH_TOKENS="${AUTH_TOKENS:-}"
TENANT_ID="${TENANT_ID:-default}"
SESSION_PREFIX="${SESSION_PREFIX:-}"
SLEEP_SECONDS="${SLEEP_SECONDS:-6.5}"
token_list="${AUTH_TOKENS:-$AUTH_TOKEN}"
token_count="$(printf '%s\n' "$token_list" | tr '|,' '\n' | sed '/^$/d' | wc -l | tr -d ' ')"

cat <<INFO
Running chat API load test
  BASE_URL=$BASE_URL
  CHAT_PATH=$CHAT_PATH
  VUS=$VUS
  DURATION=$DURATION
  TENANT_ID=$TENANT_ID
  SESSION_PREFIX=${SESSION_PREFIX:-<disabled>}
  TOKEN_COUNT=$token_count
  SLEEP_SECONDS=$SLEEP_SECONDS
INFO

if [[ "$token_count" -le 1 && "$SLEEP_SECONDS" == "6.5" ]]; then
  echo "  Note: default pacing is rate-limit-safe for a single token. Use AUTH_TOKENS for higher-concurrency load."
fi

BASE_URL="$BASE_URL" \
CHAT_PATH="$CHAT_PATH" \
VUS="$VUS" \
DURATION="$DURATION" \
PROMPTS="$PROMPTS" \
AUTH_TOKEN="$AUTH_TOKEN" \
AUTH_TOKENS="$AUTH_TOKENS" \
TENANT_ID="$TENANT_ID" \
SESSION_PREFIX="$SESSION_PREFIX" \
SLEEP_SECONDS="$SLEEP_SECONDS" \
k6 run scripts/load/chat-api-load.js
