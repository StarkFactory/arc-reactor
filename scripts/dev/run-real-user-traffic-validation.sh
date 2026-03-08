#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18081}"
TENANT_ID="${TENANT_ID:-default}"
EMAIL="${VALIDATION_EMAIL:-qa-traffic-$(date +%Y%m%d-%H%M%S)-$(shuf -i 1000-9999 -n 1)@example.com}"
PASSWORD="${VALIDATION_PASSWORD:-passw0rd!}"
NAME="${VALIDATION_USER_NAME:-QA Traffic Runner}"

# 기본 정책: 핵심 런타임 + 직원 가치 영역 + (요청자 제공 시 personalized 1회 반영)
SUITES="${VALIDATION_SUITES:-core-runtime,employee-value}"
LIMIT="${VALIDATION_CASE_LIMIT:-320}"
MODEL="${AR_REACTOR_VALIDATION_MODEL:-}"
CASE_DELAY_MS="${VALIDATION_CASE_DELAY_MS:-1800}"
RATE_LIMIT_RETRY_SEC="${VALIDATION_RATE_LIMIT_RETRY_SEC:-90}"
SHUFFLE_SEED="${VALIDATION_SHUFFLE_SEED:-17}"
SHUFFLE="${VALIDATION_SHUFFLE:-true}"

REQUESTER_EMAIL="${VALIDATION_REQUESTER_EMAIL:-${REQUESTER_EMAIL:-}}"
REQUESTER_ACCOUNT_ID="${VALIDATION_REQUESTER_ACCOUNT_ID:-${REQUESTER_ACCOUNT_ID:-}}"

REPORT_DIR="${VALIDATION_REPORT_DIR:-docs/ko/testing}"
REPORT_NAME="${VALIDATION_REPORT_NAME:-$(date -u +%Y%m%dT%H%M%SZ)-mcp-real-user-traffic}"
REPORT_MARKDOWN="${REPORT_DIR}/${REPORT_NAME}.md"
REPORT_JSON="/tmp/${REPORT_NAME}.json"

usage() {
  cat <<'EOF'
Usage: ./scripts/dev/run-real-user-traffic-validation.sh

Run real-call MCP question validation at scale for regression evidence.

Env vars:
  BASE_URL                     Arc Reactor base URL (default: http://localhost:18081)
  TENANT_ID                    Tenant header (default: default)
  VALIDATION_EMAIL             Test user email (default: auto-generated)
  VALIDATION_PASSWORD          Test user password (default: passw0rd!)
  VALIDATION_USER_NAME          Test user display name
  VALIDATION_SUITES             Comma-separated suites: core-runtime,employee-value,personalized
  VALIDATION_CASE_LIMIT         Case limit (default: 320)
  AR_REACTOR_VALIDATION_MODEL   Chat model to force (default: runtime default)
  VALIDATION_CASE_DELAY_MS      Delay between cases (default: 1800)
  VALIDATION_RATE_LIMIT_RETRY_SEC Rate-limit backoff (default: 90)
  VALIDATION_SHUFFLE_SEED       Random seed when shuffle enabled (default: 17)
  VALIDATION_SHUFFLE            true/false (default: true)
  VALIDATION_REQUESTER_EMAIL     requesterEmail metadata for personalized suite
  VALIDATION_REQUESTER_ACCOUNT_ID requesterAccountId metadata for personalized suite
  VALIDATION_REPORT_DIR          report directory
  VALIDATION_REPORT_NAME         markdown filename prefix
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

SHUFFLE_NORMALIZED="$(printf '%s' \"$SHUFFLE\" | tr '[:upper:]' '[:lower:]')"
SHUFFLE_ARG=()
if [[ "$SHUFFLE_NORMALIZED" == "true" || "$SHUFFLE_NORMALIZED" == "1" || "$SHUFFLE_NORMALIZED" == "yes" || "$SHUFFLE_NORMALIZED" == "on" ]]; then
  SHUFFLE_ARG=(--shuffle --shuffle-seed "$SHUFFLE_SEED")
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "Error: python3 is required" >&2
  exit 1
fi

mkdir -p "$REPORT_DIR"

IFS=',' read -r -a suite_tokens <<< "$SUITES"
set -- "${suite_tokens[@]}"
if [ "$#" -eq 0 ] || [ "${1:-}" = "all" ]; then
  set -- core-runtime employee-value personalized
fi

suite_args=()
for suite in "$@"; do
  suite="${suite//[[:space:]]/}"
  [[ -z "$suite" ]] && continue
  suite_args+=(--suite "$suite")
done

model_arg=()
if [[ -n "$MODEL" ]]; then
  model_arg=(--model "$MODEL")
fi

python3 scripts/dev/run-mcp-question-validation.py \
  --base-url "$BASE_URL" \
  --tenant-id "$TENANT_ID" \
  --email "$EMAIL" \
  --password "$PASSWORD" \
  --limit "$LIMIT" \
  --case-delay-ms "$CASE_DELAY_MS" \
  --rate-limit-retry-wait-sec "$RATE_LIMIT_RETRY_SEC" \
  --report-json "$REPORT_JSON" \
  --report-markdown "$REPORT_MARKDOWN" \
  --requester-email "$REQUESTER_EMAIL" \
  --requester-account-id "$REQUESTER_ACCOUNT_ID" \
  "${suite_args[@]}" \
  "${model_arg[@]}" \
  ${SHUFFLE_ARG[@]+"${SHUFFLE_ARG[@]}"}

echo "Validation complete"
echo "JSON: $REPORT_JSON"
echo "Markdown: $REPORT_MARKDOWN"
