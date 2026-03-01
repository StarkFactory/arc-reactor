#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_ID="${TENANT_ID:-default}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-passw0rd!}"
NAME="${NAME:-QA Agent E2E}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"
ADMIN_EMAIL="${ADMIN_EMAIL:-}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
REQUIRE_APPROVAL="${REQUIRE_APPROVAL:-false}"
MAX_LLM_CALLS="${MAX_LLM_CALLS:-2}"
SKIP_ASK="${SKIP_ASK:-false}"
SKIP_REACT="${SKIP_REACT:-false}"
SKIP_VECTOR="${SKIP_VECTOR:-false}"
SKIP_METRICS="${SKIP_METRICS:-false}"
SKIP_APPROVAL="${SKIP_APPROVAL:-false}"

usage() {
  cat <<'EOF'
Usage: ./scripts/dev/validate-agent-e2e.sh [options]

Validate core agent scenarios end-to-end:
  - ask      : /api/chat (single-turn response)
  - react    : /api/chat/stream (stream lifecycle)
  - vector   : /api/documents + /api/documents/search
  - metrics  : /api/ops/dashboard (admin)
  - approval : /api/approvals auth + action semantics

Options:
  --base-url <url>          Base URL (default: http://localhost:8080)
  --tenant-id <value>       Tenant ID header (default: default)
  --email <email>           Register fixed test user email (default: auto-generated)
  --password <value>        Test user password (default: passw0rd!)
  --name <value>            Test user display name
  --admin-token <token>     Admin JWT token (optional)
  --admin-email <email>     Admin login email (used when --admin-token omitted)
  --admin-password <value>  Admin login password (used when --admin-token omitted)
  --require-approval        Fail if approval endpoint is unavailable
  --max-llm-calls <n>       Upper bound for LLM-invoking scenarios (default: 2)
  --skip-ask                Skip /api/chat scenario
  --skip-react              Skip /api/chat/stream scenario
  --skip-vector             Skip vector scenarios
  --skip-metrics            Skip ops metrics scenario
  --skip-approval           Skip approval scenario
  -h, --help                Show help

Examples:
  ./scripts/dev/validate-agent-e2e.sh --base-url http://localhost:18080
  ./scripts/dev/validate-agent-e2e.sh --admin-token "$ADMIN_TOKEN" --require-approval
EOF
}

fail() {
  echo "Error: $1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

is_true() {
  local value
  value="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  [[ "$value" == "1" || "$value" == "true" || "$value" == "yes" || "$value" == "y" ]]
}

request() {
  local method="$1"
  local url="$2"
  local body_file="$3"
  shift 3
  curl -sS -X "$method" "$url" "$@" -o "$body_file" -w "%{http_code}"
}

while (($# > 0)); do
  case "$1" in
    --base-url)
      [[ $# -ge 2 ]] || fail "--base-url requires a value"
      BASE_URL="$2"
      shift 2
      ;;
    --tenant-id)
      [[ $# -ge 2 ]] || fail "--tenant-id requires a value"
      TENANT_ID="$2"
      shift 2
      ;;
    --email)
      [[ $# -ge 2 ]] || fail "--email requires a value"
      EMAIL="$2"
      shift 2
      ;;
    --password)
      [[ $# -ge 2 ]] || fail "--password requires a value"
      PASSWORD="$2"
      shift 2
      ;;
    --name)
      [[ $# -ge 2 ]] || fail "--name requires a value"
      NAME="$2"
      shift 2
      ;;
    --admin-token)
      [[ $# -ge 2 ]] || fail "--admin-token requires a value"
      ADMIN_TOKEN="$2"
      shift 2
      ;;
    --admin-email)
      [[ $# -ge 2 ]] || fail "--admin-email requires a value"
      ADMIN_EMAIL="$2"
      shift 2
      ;;
    --admin-password)
      [[ $# -ge 2 ]] || fail "--admin-password requires a value"
      ADMIN_PASSWORD="$2"
      shift 2
      ;;
    --require-approval)
      REQUIRE_APPROVAL="true"
      shift
      ;;
    --max-llm-calls)
      [[ $# -ge 2 ]] || fail "--max-llm-calls requires a value"
      MAX_LLM_CALLS="$2"
      shift 2
      ;;
    --skip-ask)
      SKIP_ASK="true"
      shift
      ;;
    --skip-react)
      SKIP_REACT="true"
      shift
      ;;
    --skip-vector)
      SKIP_VECTOR="true"
      shift
      ;;
    --skip-metrics)
      SKIP_METRICS="true"
      shift
      ;;
    --skip-approval)
      SKIP_APPROVAL="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown option: $1"
      ;;
  esac
done

require_cmd curl
require_cmd jq
require_cmd python3

if ! [[ "$MAX_LLM_CALLS" =~ ^[0-9]+$ ]]; then
  fail "--max-llm-calls must be a non-negative integer"
fi

if [[ -z "$EMAIL" ]]; then
  EMAIL="qa-agent-e2e-$(date +%s)-$RANDOM@example.com"
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

llm_calls=0

echo "[1/8] Health check"
health_file="$tmp_dir/health.json"
health_code="$(request GET "$BASE_URL/actuator/health" "$health_file")"
[[ "$health_code" == "200" ]] || fail "/actuator/health expected 200, got $health_code"
[[ "$(jq -r '.status // ""' "$health_file")" == "UP" ]] || fail "Health status is not UP"

echo "[2/8] Register test user"
register_req="$tmp_dir/register_req.json"
register_resp="$tmp_dir/register_resp.json"
cat >"$register_req" <<JSON
{"email":"$EMAIL","password":"$PASSWORD","name":"$NAME"}
JSON
register_code="$(request POST "$BASE_URL/api/auth/register" "$register_resp" \
  -H "Content-Type: application/json" \
  --data-binary "@$register_req")"
[[ "$register_code" == "201" ]] || fail "/api/auth/register expected 201, got $register_code"
user_token="$(jq -r '.token // ""' "$register_resp")"
[[ -n "$user_token" ]] || fail "Register response missing token"

echo "[3/8] Resolve admin token (optional)"
resolved_admin_token="$ADMIN_TOKEN"
if [[ -z "$resolved_admin_token" && -n "$ADMIN_EMAIL" && -n "$ADMIN_PASSWORD" ]]; then
  admin_login_req="$tmp_dir/admin_login_req.json"
  admin_login_resp="$tmp_dir/admin_login_resp.json"
  cat >"$admin_login_req" <<JSON
{"email":"$ADMIN_EMAIL","password":"$ADMIN_PASSWORD"}
JSON
  admin_login_code="$(request POST "$BASE_URL/api/auth/login" "$admin_login_resp" \
    -H "Content-Type: application/json" \
    --data-binary "@$admin_login_req")"
  [[ "$admin_login_code" == "200" ]] || fail "Admin login failed with status $admin_login_code"
  resolved_admin_token="$(jq -r '.token // ""' "$admin_login_resp")"
fi

if [[ -n "$resolved_admin_token" ]]; then
  echo "      Admin token is available"
else
  echo "      Admin token is not available (admin-only scenarios may be skipped)"
fi

if ! is_true "$SKIP_ASK"; then
  echo "[4/8] Ask scenario (/api/chat)"
  ((llm_calls += 1))
  if (( llm_calls > MAX_LLM_CALLS )); then
    fail "LLM call budget exceeded before ask scenario ($llm_calls > $MAX_LLM_CALLS)"
  fi
  ask_code_token="AGENT_E2E_ASK_$(date +%s)"
  ask_req="$tmp_dir/ask_req.json"
  ask_resp="$tmp_dir/ask_resp.json"
  cat >"$ask_req" <<JSON
{"message":"Repeat this token exactly once: $ask_code_token","userId":"qa-agent-e2e-user"}
JSON
  ask_code="$(request POST "$BASE_URL/api/chat" "$ask_resp" \
    -H "Authorization: Bearer $user_token" \
    -H "X-Tenant-Id: $TENANT_ID" \
    -H "Content-Type: application/json" \
    --data-binary "@$ask_req")"
  [[ "$ask_code" == "200" ]] || fail "/api/chat expected 200, got $ask_code"
  [[ "$(jq -r '.success // false' "$ask_resp")" == "true" ]] || fail "Ask scenario returned success=false"
  ask_content="$(jq -r '.content // ""' "$ask_resp")"
  [[ "$ask_content" == *"$ask_code_token"* ]] || fail "Ask content does not contain expected token"
else
  echo "[4/8] Ask scenario skipped"
fi

if ! is_true "$SKIP_REACT"; then
  echo "[5/8] ReAct streaming scenario (/api/chat/stream)"
  ((llm_calls += 1))
  if (( llm_calls > MAX_LLM_CALLS )); then
    fail "LLM call budget exceeded before react scenario ($llm_calls > $MAX_LLM_CALLS)"
  fi
  react_req="$tmp_dir/react_req.json"
  react_resp="$tmp_dir/react_stream.txt"
  cat >"$react_req" <<JSON
{"message":"Stream a short confirmation with one sentence.","userId":"qa-agent-e2e-user"}
JSON
  react_code="$(curl -sS -o "$react_resp" -w "%{http_code}" \
    -H "Authorization: Bearer $user_token" \
    -H "X-Tenant-Id: $TENANT_ID" \
    -H "Accept: text/event-stream" \
    -H "Content-Type: application/json" \
    --data-binary "@$react_req" \
    "$BASE_URL/api/chat/stream")"
  [[ "$react_code" == "200" ]] || fail "/api/chat/stream expected 200, got $react_code"
  react_body="$(cat "$react_resp")"
  [[ "$react_body" == *"done"* ]] || fail "Stream response does not contain done marker"
else
  echo "[5/8] ReAct streaming scenario skipped"
fi

if ! is_true "$SKIP_VECTOR"; then
  echo "[6/8] Vector scenario (/api/documents + /api/documents/search)"
  if [[ -z "$resolved_admin_token" ]]; then
    echo "      Skipped vector scenario: admin token is required for /api/documents write"
  else
    vector_code_token="AGENT_E2E_VECTOR_$(date +%s)"
    add_req="$tmp_dir/vector_add_req.json"
    add_resp="$tmp_dir/vector_add_resp.json"
    search_req="$tmp_dir/vector_search_req.json"
    search_resp="$tmp_dir/vector_search_resp.json"

    cat >"$add_req" <<JSON
{"title":"Agent E2E $vector_code_token","content":"Arc Reactor vector check token: $vector_code_token"}
JSON
    add_code="$(request POST "$BASE_URL/api/documents" "$add_resp" \
      -H "Authorization: Bearer $resolved_admin_token" \
      -H "X-Tenant-Id: $TENANT_ID" \
      -H "Content-Type: application/json" \
      --data-binary "@$add_req")"

    if [[ "$add_code" == "404" ]]; then
      echo "      Skipped vector scenario: /api/documents unavailable (RAG ingestion disabled)"
    else
      [[ "$add_code" == "201" ]] || fail "/api/documents expected 201, got $add_code"

      cat >"$search_req" <<JSON
{"query":"$vector_code_token","topK":3}
JSON
      search_code="$(request POST "$BASE_URL/api/documents/search" "$search_resp" \
        -H "Authorization: Bearer $user_token" \
        -H "X-Tenant-Id: $TENANT_ID" \
        -H "Content-Type: application/json" \
        --data-binary "@$search_req")"
      [[ "$search_code" == "200" ]] || fail "/api/documents/search expected 200, got $search_code"
      grep -q "$vector_code_token" "$search_resp" || fail "Vector search response missing expected token"
    fi
  fi
else
  echo "[6/8] Vector scenario skipped"
fi

if ! is_true "$SKIP_METRICS"; then
  echo "[7/8] Metrics scenario (/api/ops/dashboard)"
  if [[ -z "$resolved_admin_token" ]]; then
    echo "      Skipped metrics scenario: admin token is required"
  else
    metrics_resp="$tmp_dir/metrics_resp.json"
    metrics_code="$(request GET "$BASE_URL/api/ops/dashboard" "$metrics_resp" \
      -H "Authorization: Bearer $resolved_admin_token" \
      -H "X-Tenant-Id: $TENANT_ID")"
    [[ "$metrics_code" == "200" ]] || fail "/api/ops/dashboard expected 200, got $metrics_code"
    [[ "$(jq -r '(.metrics | type) == \"array\"' "$metrics_resp")" == "true" ]] \
      || fail "Ops dashboard response missing metrics array"
  fi
else
  echo "[7/8] Metrics scenario skipped"
fi

if ! is_true "$SKIP_APPROVAL"; then
  echo "[8/8] Approval scenario (/api/approvals)"
  approvals_user_resp="$tmp_dir/approvals_user_resp.json"
  approvals_user_code="$(request GET "$BASE_URL/api/approvals" "$approvals_user_resp" \
    -H "Authorization: Bearer $user_token" \
    -H "X-Tenant-Id: $TENANT_ID")"

  if [[ "$approvals_user_code" == "404" ]]; then
    if is_true "$REQUIRE_APPROVAL"; then
      fail "Approval scenario required but /api/approvals is unavailable (404)"
    fi
    echo "      Skipped approval scenario: feature disabled"
  else
    [[ "$approvals_user_code" == "200" ]] || fail "/api/approvals expected 200 or 404, got $approvals_user_code"
    [[ "$(jq -r '(type == \"array\")' "$approvals_user_resp")" == "true" ]] \
      || fail "Approval list response for user must be an array"

    user_approve_resp="$tmp_dir/approvals_user_approve.json"
    user_approve_code="$(request POST "$BASE_URL/api/approvals/non-existent/approve" "$user_approve_resp" \
      -H "Authorization: Bearer $user_token" \
      -H "X-Tenant-Id: $TENANT_ID" \
      -H "Content-Type: application/json" \
      --data '{"modifiedArguments":{"safe":true}}')"
    [[ "$user_approve_code" == "403" ]] || fail "Non-admin approval action must return 403"

    if [[ -z "$resolved_admin_token" ]]; then
      if is_true "$REQUIRE_APPROVAL"; then
        fail "Approval scenario required but admin token is not available"
      fi
      echo "      Partial approval validation only: admin token unavailable"
    else
      admin_list_resp="$tmp_dir/approvals_admin_list.json"
      admin_list_code="$(request GET "$BASE_URL/api/approvals" "$admin_list_resp" \
        -H "Authorization: Bearer $resolved_admin_token" \
        -H "X-Tenant-Id: $TENANT_ID")"
      [[ "$admin_list_code" == "200" ]] || fail "Admin approval list must return 200"

      admin_approve_resp="$tmp_dir/approvals_admin_approve.json"
      admin_approve_code="$(request POST "$BASE_URL/api/approvals/non-existent/approve" "$admin_approve_resp" \
        -H "Authorization: Bearer $resolved_admin_token" \
        -H "X-Tenant-Id: $TENANT_ID" \
        -H "Content-Type: application/json" \
        --data '{"modifiedArguments":{"safe":true}}')"
      [[ "$admin_approve_code" == "200" ]] || fail "Admin approval action must return 200"
      [[ "$(jq -r '.success // true' "$admin_approve_resp")" == "false" ]] \
        || fail "Approving non-existent approval ID should return success=false"
    fi
  fi
else
  echo "[8/8] Approval scenario skipped"
fi

echo "Agent E2E validation passed."
echo "Base URL: $BASE_URL"
echo "Tenant ID: $TENANT_ID"
echo "User email: $EMAIL"
echo "LLM calls executed: $llm_calls (budget: $MAX_LLM_CALLS)"
