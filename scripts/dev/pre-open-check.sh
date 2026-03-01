#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

BASE_URL=""
RUN_RUNTIME_CONTRACT=0
RUN_AGENT_E2E=0
NO_DAEMON=1

usage() {
  cat <<'EOF'
Usage: ./scripts/dev/pre-open-check.sh [options]

Runs open-source preflight checks before publishing.

Checks:
  1) Docs consistency checks
  2) Kotlin compile checks
  3) Unit/integration-default test suite
  4) Secret scan (gitleaks baseline)
  5) Optional runtime contract smoke (no LLM call)
  6) Optional agent e2e scenarios (ask/react/approval/vector/metrics)

Options:
  --with-runtime              Run runtime contract check
  --with-agent-e2e            Run agent scenario validation check
  --base-url <url>            Base URL for runtime contract (default: http://localhost:8080)
  --daemon                    Use Gradle daemon (default: --no-daemon)
  -h, --help                  Show help

Examples:
  ./scripts/dev/pre-open-check.sh
  ./scripts/dev/pre-open-check.sh --with-runtime --base-url http://localhost:18080
  ./scripts/dev/pre-open-check.sh --with-runtime --with-agent-e2e
EOF
}

while (($# > 0)); do
  case "$1" in
    --with-runtime)
      RUN_RUNTIME_CONTRACT=1
      shift
      ;;
    --with-agent-e2e)
      RUN_AGENT_E2E=1
      shift
      ;;
    --base-url)
      [[ $# -ge 2 ]] || { echo "Error: --base-url requires a value" >&2; exit 1; }
      BASE_URL="$2"
      shift 2
      ;;
    --daemon)
      NO_DAEMON=0
      shift
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

require_cmd bash
require_cmd python3
require_cmd ./gradlew
require_cmd gitleaks

if [[ -z "$BASE_URL" ]]; then
  BASE_URL="http://localhost:8080"
fi

gradle_opts=()
if [[ "$NO_DAEMON" == "1" ]]; then
  gradle_opts+=(--no-daemon)
fi

echo "[1/6] Docs checks"
bash scripts/dev/check-docs.sh

echo "[2/6] Compile checks"
./gradlew compileKotlin compileTestKotlin "${gradle_opts[@]}"

echo "[3/6] Test suite"
./gradlew test "${gradle_opts[@]}"

echo "[4/6] Secret scan"
gitleaks git --redact --baseline-path .gitleaks-baseline.json --exit-code 1

if [[ "$RUN_RUNTIME_CONTRACT" == "1" ]]; then
  echo "[5/6] Runtime contract smoke"
  ./scripts/dev/validate-runtime-contract.sh --base-url "$BASE_URL"
else
  echo "[5/6] Runtime contract smoke skipped (use --with-runtime to enable)"
fi

if [[ "$RUN_AGENT_E2E" == "1" ]]; then
  echo "[6/6] Agent e2e scenarios"
  ./scripts/dev/validate-agent-e2e.sh --base-url "$BASE_URL"
else
  echo "[6/6] Agent e2e scenarios skipped (use --with-agent-e2e to enable)"
fi

echo "Pre-open checks passed."
