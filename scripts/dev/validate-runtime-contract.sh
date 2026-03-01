#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL=""
PASSWORD="${PASSWORD:-passw0rd!}"
NAME="${NAME:-QA Contract}"
TENANT_ID="${TENANT_ID:-default}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"

usage() {
  cat <<'EOF'
Usage: ./scripts/dev/validate-runtime-contract.sh [options]

Validates runtime auth and admin-access contracts without triggering LLM calls.

Options:
  --base-url <url>       Base URL (default: http://localhost:8080)
  --email <email>        Register with a fixed email (default: auto-generated)
  --password <value>     Register password (default: passw0rd!)
  --name <value>         Register display name (default: QA Contract)
  --tenant-id <value>    Tenant header for authenticated probe (default: default)
  --admin-token <token>  Optional admin JWT. If provided, checks admin MCP read path returns 200.
  -h, --help             Show help

Examples:
  ./scripts/dev/validate-runtime-contract.sh
  ./scripts/dev/validate-runtime-contract.sh --base-url http://localhost:18080
  ./scripts/dev/validate-runtime-contract.sh --admin-token "$ADMIN_TOKEN"
EOF
}

fail() {
  echo "Error: $1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

request() {
  local method="$1"
  local url="$2"
  local body_file="$3"
  shift 3
  curl -sS -X "$method" "$url" "$@" -o "$body_file" -w "%{http_code}"
}

extract_json_field() {
  local file="$1"
  local field="$2"
  python3 - "$file" "$field" <<'PY'
import json
import sys

path, key = sys.argv[1], sys.argv[2]
try:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    value = data
    for part in key.split("."):
        value = value[part]
    if value is None:
        print("")
    else:
        print(str(value))
except Exception:
    print("")
PY
}

while (($# > 0)); do
  case "$1" in
    --base-url)
      [[ $# -ge 2 ]] || fail "--base-url requires a value"
      BASE_URL="$2"
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
    --tenant-id)
      [[ $# -ge 2 ]] || fail "--tenant-id requires a value"
      TENANT_ID="$2"
      shift 2
      ;;
    --admin-token)
      [[ $# -ge 2 ]] || fail "--admin-token requires a value"
      ADMIN_TOKEN="$2"
      shift 2
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
require_cmd python3

if [[ -z "$EMAIL" ]]; then
  EMAIL="qa-contract-$(date +%s)-$RANDOM@example.com"
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

echo "[1/5] Health check"
health_file="$tmp_dir/health.json"
health_code="$(request GET "$BASE_URL/actuator/health" "$health_file")"
[[ "$health_code" == "200" ]] || fail "/actuator/health expected 200, got $health_code"
health_status="$(extract_json_field "$health_file" "status")"
[[ "$health_status" == "UP" ]] || fail "/actuator/health status expected UP, got '$health_status'"

echo "[2/5] Unauthenticated guard check"
models_unauth_file="$tmp_dir/models_unauth.json"
models_unauth_code="$(request GET "$BASE_URL/api/models" "$models_unauth_file")"
[[ "$models_unauth_code" == "401" ]] || fail "/api/models without token expected 401, got $models_unauth_code"

echo "[3/5] Register user and extract token"
register_file="$tmp_dir/register.json"
register_payload="$tmp_dir/register_payload.json"
cat >"$register_payload" <<JSON
{"email":"$EMAIL","password":"$PASSWORD","name":"$NAME"}
JSON
register_code="$(request POST "$BASE_URL/api/auth/register" "$register_file" \
  -H "Content-Type: application/json" \
  --data-binary "@$register_payload")"
[[ "$register_code" == "201" ]] || fail "/api/auth/register expected 201, got $register_code"
user_token="$(extract_json_field "$register_file" "token")"
[[ -n "$user_token" ]] || fail "Register response did not include token"

echo "[4/5] Authenticated user path check"
models_auth_file="$tmp_dir/models_auth.json"
models_auth_code="$(request GET "$BASE_URL/api/models" "$models_auth_file" \
  -H "Authorization: Bearer $user_token" \
  -H "X-Tenant-Id: $TENANT_ID")"
[[ "$models_auth_code" == "200" ]] || fail "/api/models with token expected 200, got $models_auth_code"

echo "[5/5] Admin guard check for MCP inventory read"
mcp_user_file="$tmp_dir/mcp_user.json"
mcp_user_code="$(request GET "$BASE_URL/api/mcp/servers" "$mcp_user_file" \
  -H "Authorization: Bearer $user_token")"
[[ "$mcp_user_code" == "403" ]] || fail "/api/mcp/servers with USER token expected 403, got $mcp_user_code"

if [[ -n "$ADMIN_TOKEN" ]]; then
  echo "      Optional admin token check"
  mcp_admin_file="$tmp_dir/mcp_admin.json"
  mcp_admin_code="$(request GET "$BASE_URL/api/mcp/servers" "$mcp_admin_file" \
    -H "Authorization: Bearer $ADMIN_TOKEN")"
  [[ "$mcp_admin_code" == "200" ]] || fail "/api/mcp/servers with ADMIN token expected 200, got $mcp_admin_code"
fi

echo "Runtime contract validation passed."
echo "Base URL: $BASE_URL"
echo "Registered test user: $EMAIL"
