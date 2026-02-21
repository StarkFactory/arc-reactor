#!/usr/bin/env bash
set -euo pipefail

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is required. Install: https://k6.io/docs/get-started/installation/"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
VUS="${VUS:-5}"
DURATION="${DURATION:-1m}"
CHAT_PATH="${CHAT_PATH:-/api/chat}"
PROMPTS="${PROMPTS:-What is 2 + 2?|Summarize policy controls for write tools}"
AUTH_TOKEN="${AUTH_TOKEN:-}"

cat <<INFO
Running chat API load test
  BASE_URL=$BASE_URL
  CHAT_PATH=$CHAT_PATH
  VUS=$VUS
  DURATION=$DURATION
INFO

BASE_URL="$BASE_URL" \
CHAT_PATH="$CHAT_PATH" \
VUS="$VUS" \
DURATION="$DURATION" \
PROMPTS="$PROMPTS" \
AUTH_TOKEN="$AUTH_TOKEN" \
k6 run scripts/load/chat-api-load.js
