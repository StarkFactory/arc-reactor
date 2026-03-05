#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUTPUT_PATH="${OUTPUT_PATH:-artifacts/cto/cto-security-onepager-${TIMESTAMP}.md}"
BASELINE_SUMMARY="${BASELINE_SUMMARY:-artifacts/security-baseline/latest-fast/summary.md}"
AUTH_K6_SUMMARY="${AUTH_K6_SUMMARY:-artifacts/k6/auth-rate-limit-summary.json}"
CHAT_GUARD_K6_SUMMARY="${CHAT_GUARD_K6_SUMMARY:-artifacts/k6/chat-guard-summary.json}"
CHAT_STREAM_K6_SUMMARY="${CHAT_STREAM_K6_SUMMARY:-artifacts/k6/chat-stream-security-summary.json}"
CHAT_STREAM_SOAK_K6_SUMMARY="${CHAT_STREAM_SOAK_K6_SUMMARY:-}"
SERVICE_NAME="${SERVICE_NAME:-arc-reactor}"

usage() {
  cat <<'EOF'
Usage: ./scripts/dev/generate-cto-security-onepager.sh [options]

Generate a one-page CTO security summary from baseline and k6 artifacts.

Options:
  --output <path>              Output markdown path
  --baseline-summary <path>    Baseline summary markdown
  --auth-k6 <path>             k6 summary json (auth rate-limit)
  --chat-guard-k6 <path>       k6 summary json (chat guard/filtering)
  --chat-stream-k6 <path>      k6 summary json (chat stream security)
  --chat-stream-soak-k6 <path> k6 summary json (chat stream security soak)
  --service-name <name>        Service name label
  -h, --help                   Show help
EOF
}

while (($# > 0)); do
  case "$1" in
    --output)
      [[ $# -ge 2 ]] || { echo "Error: --output requires a value" >&2; exit 1; }
      OUTPUT_PATH="$2"
      shift 2
      ;;
    --baseline-summary)
      [[ $# -ge 2 ]] || { echo "Error: --baseline-summary requires a value" >&2; exit 1; }
      BASELINE_SUMMARY="$2"
      shift 2
      ;;
    --auth-k6)
      [[ $# -ge 2 ]] || { echo "Error: --auth-k6 requires a value" >&2; exit 1; }
      AUTH_K6_SUMMARY="$2"
      shift 2
      ;;
    --chat-guard-k6)
      [[ $# -ge 2 ]] || { echo "Error: --chat-guard-k6 requires a value" >&2; exit 1; }
      CHAT_GUARD_K6_SUMMARY="$2"
      shift 2
      ;;
    --chat-stream-k6)
      [[ $# -ge 2 ]] || { echo "Error: --chat-stream-k6 requires a value" >&2; exit 1; }
      CHAT_STREAM_K6_SUMMARY="$2"
      shift 2
      ;;
    --chat-stream-soak-k6)
      [[ $# -ge 2 ]] || { echo "Error: --chat-stream-soak-k6 requires a value" >&2; exit 1; }
      CHAT_STREAM_SOAK_K6_SUMMARY="$2"
      shift 2
      ;;
    --service-name)
      [[ $# -ge 2 ]] || { echo "Error: --service-name requires a value" >&2; exit 1; }
      SERVICE_NAME="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Error: unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Error: required command not found: $1" >&2
    exit 1
  }
}

require_cmd jq
require_cmd git

mkdir -p "$(dirname "$OUTPUT_PATH")"

read_summary_value() {
  local file="$1"
  local key="$2"
  if [[ ! -f "$file" ]]; then
    echo ""
    return 0
  fi
  sed -n "s/^- ${key}: //p" "$file" | head -n 1
}

k6_metric() {
  local file="$1"
  local metric="$2"
  local field="$3"
  if [[ ! -f "$file" ]]; then
    echo ""
    return 0
  fi
  jq -r "
    if .metrics[\"$metric\"] == null then
      \"\"
    elif .metrics[\"$metric\"].values != null and .metrics[\"$metric\"].values[\"$field\"] != null then
      .metrics[\"$metric\"].values[\"$field\"]
    elif .metrics[\"$metric\"][\"$field\"] != null then
      .metrics[\"$metric\"][\"$field\"]
    elif \"$field\" == \"rate\" and .metrics[\"$metric\"].value != null then
      .metrics[\"$metric\"].value
    else
      \"\"
    end
  " "$file" 2>/dev/null || true
}

float_lt() {
  local value="$1"
  local threshold="$2"
  awk -v v="$value" -v t="$threshold" 'BEGIN { if (v+0 < t+0) exit 0; exit 1 }'
}

float_gt() {
  local value="$1"
  local threshold="$2"
  awk -v v="$value" -v t="$threshold" 'BEGIN { if (v+0 > t+0) exit 0; exit 1 }'
}

format_pct() {
  local value="$1"
  if [[ -z "$value" ]]; then
    echo "N/A"
    return 0
  fi
  awk -v v="$value" 'BEGIN { printf "%.2f%%", (v+0)*100 }'
}

format_num() {
  local value="$1"
  if [[ -z "$value" ]]; then
    echo "N/A"
    return 0
  fi
  awk -v v="$value" 'BEGIN { printf "%.2f", (v+0) }'
}

evidence_missing=0
any_failure=0

baseline_status="MISSING"
baseline_note="baseline summary file not found"
baseline_gitleaks_findings="N/A"
baseline_trivy_findings="N/A"
baseline_gitleaks_exit="N/A"
baseline_trivy_exit="N/A"

if [[ -f "$BASELINE_SUMMARY" ]]; then
  baseline_gitleaks_exit="$(read_summary_value "$BASELINE_SUMMARY" "gitleaksExitCode")"
  baseline_trivy_exit="$(read_summary_value "$BASELINE_SUMMARY" "trivyExitCode")"
  baseline_gitleaks_findings="$(read_summary_value "$BASELINE_SUMMARY" "gitleaksFindings")"
  baseline_trivy_findings="$(read_summary_value "$BASELINE_SUMMARY" "trivyFindings")"

  if [[ "$baseline_gitleaks_exit" == "0" && "$baseline_trivy_exit" == "0" &&
    "$baseline_gitleaks_findings" == "0" && "$baseline_trivy_findings" == "0" ]]; then
    baseline_status="PASS"
    baseline_note="no high/critical findings"
  else
    baseline_status="FAIL"
    baseline_note="scan failed or findings present"
    any_failure=1
  fi
else
  evidence_missing=1
fi

eval_auth_status() {
  if [[ ! -f "$AUTH_K6_SUMMARY" ]]; then
    echo "MISSING|summary not found|N/A|N/A"
    return 0
  fi
  local unexpected rate_limited
  unexpected="$(k6_metric "$AUTH_K6_SUMMARY" "auth_unexpected_status_ratio" "rate")"
  rate_limited="$(k6_metric "$AUTH_K6_SUMMARY" "auth_rate_limited_ratio" "rate")"
  if [[ -z "$unexpected" || -z "$rate_limited" ]]; then
    echo "FAIL|required metrics missing|$(format_pct "$unexpected")|$(format_pct "$rate_limited")"
    return 0
  fi

  if float_lt "$unexpected" "0.01" && float_gt "$rate_limited" "0.10"; then
    echo "PASS|unexpected<1%, rate-limited>10%|$(format_pct "$unexpected")|$(format_pct "$rate_limited")"
  else
    any_failure=1
    echo "FAIL|threshold mismatch|$(format_pct "$unexpected")|$(format_pct "$rate_limited")"
  fi
}

eval_contract_status() {
  local file="$1"
  local failure_metric="$2"
  local unexpected_metric="$3"
  if [[ ! -f "$file" ]]; then
    echo "MISSING|summary not found|N/A|N/A"
    return 0
  fi
  local contract_failure unexpected
  contract_failure="$(k6_metric "$file" "$failure_metric" "rate")"
  unexpected="$(k6_metric "$file" "$unexpected_metric" "rate")"
  if [[ -z "$contract_failure" || -z "$unexpected" ]]; then
    echo "FAIL|required metrics missing|$(format_pct "$contract_failure")|$(format_pct "$unexpected")"
    return 0
  fi

  if float_lt "$contract_failure" "0.01" && float_lt "$unexpected" "0.01"; then
    echo "PASS|contract<1%, unexpected<1%|$(format_pct "$contract_failure")|$(format_pct "$unexpected")"
  else
    any_failure=1
    echo "FAIL|threshold mismatch|$(format_pct "$contract_failure")|$(format_pct "$unexpected")"
  fi
}

auth_eval="$(eval_auth_status)"
auth_status="${auth_eval%%|*}"
auth_rest="${auth_eval#*|}"
auth_note="${auth_rest%%|*}"
auth_rest="${auth_rest#*|}"
auth_unexpected="${auth_rest%%|*}"
auth_rate_limited="${auth_rest#*|}"

chat_guard_eval="$(eval_contract_status "$CHAT_GUARD_K6_SUMMARY" "chat_guard_contract_failure_ratio" "chat_guard_unexpected_status_ratio")"
chat_guard_status="${chat_guard_eval%%|*}"
chat_guard_rest="${chat_guard_eval#*|}"
chat_guard_note="${chat_guard_rest%%|*}"
chat_guard_rest="${chat_guard_rest#*|}"
chat_guard_contract_fail="${chat_guard_rest%%|*}"
chat_guard_unexpected="${chat_guard_rest#*|}"

chat_stream_eval="$(eval_contract_status "$CHAT_STREAM_K6_SUMMARY" "chat_stream_contract_failure_ratio" "chat_stream_unexpected_status_ratio")"
chat_stream_status="${chat_stream_eval%%|*}"
chat_stream_rest="${chat_stream_eval#*|}"
chat_stream_note="${chat_stream_rest%%|*}"
chat_stream_rest="${chat_stream_rest#*|}"
chat_stream_contract_fail="${chat_stream_rest%%|*}"
chat_stream_unexpected="${chat_stream_rest#*|}"

chat_stream_soak_status="SKIPPED"
chat_stream_soak_note="not provided"
chat_stream_soak_contract_fail="N/A"
chat_stream_soak_unexpected="N/A"
if [[ -n "$CHAT_STREAM_SOAK_K6_SUMMARY" ]]; then
  chat_stream_soak_eval="$(eval_contract_status "$CHAT_STREAM_SOAK_K6_SUMMARY" "chat_stream_contract_failure_ratio" "chat_stream_unexpected_status_ratio")"
  chat_stream_soak_status="${chat_stream_soak_eval%%|*}"
  chat_stream_soak_rest="${chat_stream_soak_eval#*|}"
  chat_stream_soak_note="${chat_stream_soak_rest%%|*}"
  chat_stream_soak_rest="${chat_stream_soak_rest#*|}"
  chat_stream_soak_contract_fail="${chat_stream_soak_rest%%|*}"
  chat_stream_soak_unexpected="${chat_stream_soak_rest#*|}"
fi

for status in "$baseline_status" "$auth_status" "$chat_guard_status" "$chat_stream_status" "$chat_stream_soak_status"; do
  if [[ "$status" == "FAIL" ]]; then
    any_failure=1
  fi
  if [[ "$status" == "MISSING" ]]; then
    evidence_missing=1
  fi
done

verdict="GO"
if [[ "$any_failure" -eq 1 ]]; then
  verdict="NO-GO"
elif [[ "$evidence_missing" -eq 1 ]]; then
  verdict="PENDING_EVIDENCE"
fi

git_sha="$(git rev-parse --short HEAD)"
git_branch="$(git rev-parse --abbrev-ref HEAD)"
generated_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

cat >"$OUTPUT_PATH" <<EOF
# CTO Security One-Pager

- service: $SERVICE_NAME
- generatedAt: $generated_at
- gitBranch: $git_branch
- gitCommit: $git_sha
- finalVerdict: **$verdict**

## Gate Summary

| Gate | Status | Evidence | Note |
|---|---|---|---|
| Security Baseline (Gitleaks + Trivy) | $baseline_status | $BASELINE_SUMMARY | $baseline_note |
| Auth Brute-force (k6) | $auth_status | $AUTH_K6_SUMMARY | $auth_note |
| Chat Guard/Filtering Contract (k6) | $chat_guard_status | $CHAT_GUARD_K6_SUMMARY | $chat_guard_note |
| Chat Stream Security Contract (k6) | $chat_stream_status | $CHAT_STREAM_K6_SUMMARY | $chat_stream_note |
| Chat Stream Security Soak (k6) | $chat_stream_soak_status | ${CHAT_STREAM_SOAK_K6_SUMMARY:-N/A} | $chat_stream_soak_note |

## Key Metrics

| Metric | Value |
|---|---|
| gitleaksExitCode | $baseline_gitleaks_exit |
| trivyExitCode | $baseline_trivy_exit |
| gitleaksFindings | $baseline_gitleaks_findings |
| trivyFindings | $baseline_trivy_findings |
| auth_unexpected_status_ratio | $auth_unexpected |
| auth_rate_limited_ratio | $auth_rate_limited |
| chat_guard_contract_failure_ratio | $chat_guard_contract_fail |
| chat_guard_unexpected_status_ratio | $chat_guard_unexpected |
| chat_stream_contract_failure_ratio | $chat_stream_contract_fail |
| chat_stream_unexpected_status_ratio | $chat_stream_unexpected |
| chat_stream_soak_contract_failure_ratio | $chat_stream_soak_contract_fail |
| chat_stream_soak_unexpected_status_ratio | $chat_stream_soak_unexpected |

## Decision Rule

- \`GO\`: all provided gates are PASS with zero findings
- \`PENDING_EVIDENCE\`: no failure, but one or more evidence files missing
- \`NO-GO\`: any FAIL gate or non-zero baseline findings
EOF

echo "Generated: $OUTPUT_PATH"
