#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
STREAM_PATH="${STREAM_PATH:-/api/chat/stream}"
TENANT_ID="${TENANT_ID:-default}"
OTHER_TENANT_ID="${OTHER_TENANT_ID:-other-tenant}"
MODE="${MODE:-mixed}"
OVERSIZED_CHARS="${OVERSIZED_CHARS:-15000}"
VUS="${VUS:-10}"
DURATION="${DURATION:-20m}"
SLEEP_SECONDS="${SLEEP_SECONDS:-0.05}"
SUMMARY_EXPORT="${SUMMARY_EXPORT:-artifacts/k6/chat-stream-security-soak-summary.json}"

if [[ -z "$AUTH_TOKEN" ]]; then
  echo "AUTH_TOKEN is required."
  echo "Example:"
  echo "  AUTH_TOKEN=\"<jwt>\" ./scripts/load/run-chat-stream-security-soak-test.sh"
  exit 1
fi

cat <<INFO
Running chat stream security SOAK test
  BASE_URL=$BASE_URL
  STREAM_PATH=$STREAM_PATH
  MODE=$MODE
  VUS=$VUS
  DURATION=$DURATION
  SUMMARY_EXPORT=$SUMMARY_EXPORT
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
SUMMARY_EXPORT="$SUMMARY_EXPORT" \
./scripts/load/run-chat-stream-security-load-test.sh
