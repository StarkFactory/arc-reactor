#!/usr/bin/env bash
set -euo pipefail

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is required. Install: https://k6.io/docs/get-started/installation/"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
LOGIN_PATH="${LOGIN_PATH:-/api/auth/login}"
VUS="${VUS:-10}"
DURATION="${DURATION:-1m}"
SLEEP_SECONDS="${SLEEP_SECONDS:-0.1}"
FORWARDED_FOR="${FORWARDED_FOR:-198.51.100.77}"
SUMMARY_EXPORT="${SUMMARY_EXPORT:-}"

k6_args=()
if [[ -n "$SUMMARY_EXPORT" ]]; then
  mkdir -p "$(dirname "$SUMMARY_EXPORT")"
  k6_args+=(--summary-export "$SUMMARY_EXPORT")
fi

cat <<INFO
Running auth rate-limit load test
  BASE_URL=$BASE_URL
  LOGIN_PATH=$LOGIN_PATH
  VUS=$VUS
  DURATION=$DURATION
  FORWARDED_FOR=$FORWARDED_FOR
  SUMMARY_EXPORT=${SUMMARY_EXPORT:-<none>}
INFO

BASE_URL="$BASE_URL" \
LOGIN_PATH="$LOGIN_PATH" \
VUS="$VUS" \
DURATION="$DURATION" \
SLEEP_SECONDS="$SLEEP_SECONDS" \
FORWARDED_FOR="$FORWARDED_FOR" \
k6 run "${k6_args[@]}" scripts/load/auth-rate-limit-load.js
