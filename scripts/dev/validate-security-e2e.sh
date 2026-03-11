#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_ID="${TENANT_ID:-default}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-passw0rd!}"
NAME="${NAME:-QA Security E2E}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"
STRICT_HEALTH="${STRICT_HEALTH:-false}"
CHECK_AUTH_RATE_LIMIT="${CHECK_AUTH_RATE_LIMIT:-true}"
AUTH_RATE_LIMIT_PER_MINUTE="${AUTH_RATE_LIMIT_PER_MINUTE:-10}"

usage() {
  cat <<'EOF'
Usage: ./scripts/dev/validate-security-e2e.sh [options]

Run security-focused E2E checks without requiring LLM response quality assertions.

Checks:
  - Unauthenticated access is rejected on protected endpoints
  - Authenticated USER can access user paths but not admin paths (403)
  - Tampered token is rejected (401)
  - Tenant mismatch on chat endpoint fails closed (400)
  - Oversized chat input is blocked by guard (400)
  - Logout revokes token (same token becomes unauthorized)
  - Security headers are present on responses

Options:
  --base-url <url>       Base URL (default: http://localhost:8080)
  --tenant-id <value>    Tenant header for authenticated requests (default: default)
  --email <email>        Register/login test account email (default: auto-generated)
  --password <value>     Account password (default: passw0rd!)
  --name <value>         Account display name
  --admin-token <token>  Optional admin token for positive admin-path check
  --strict-health        Fail when /actuator/health is not 200/503
  --skip-auth-rate-limit Skip brute-force rate-limit probe on auth endpoints
  -h, --help             Show help

Examples:
  ./scripts/dev/validate-security-e2e.sh --base-url http://localhost:8080
  ./scripts/dev/validate-security-e2e.sh --admin-token "$ADMIN_TOKEN"
EOF
}

fail() {
  echo "Error: $1" >&2
  exit 1
}

is_true() {
  local value
  value="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  [[ "$value" == "1" || "$value" == "true" || "$value" == "yes" || "$value" == "y" ]]
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

login_with_credentials() {
  local email="$1"
  local password="$2"
  local response_file="$3"
  local payload_file="$tmp_dir/login_payload_${RANDOM}.json"
  cat >"$payload_file" <<JSON
{"email":"$email","password":"$password"}
JSON
  request POST "$BASE_URL/api/auth/login" "$response_file" \
    -H "Content-Type: application/json" \
    --data-binary "@$payload_file"
}

json_field() {
  local file="$1"
  local expr="$2"
  local value
  value="$(jq -r "$expr" "$file" 2>/dev/null || true)"
  if [[ "$value" == "null" ]]; then
    echo ""
  else
    echo "$value"
  fi
}

assert_status() {
  local actual="$1"
  local expected="$2"
  local context="$3"
  [[ "$actual" == "$expected" ]] || fail "$context expected $expected, got $actual"
}

step_index=0
total_steps=10

step() {
  step_index=$((step_index + 1))
  echo "[$step_index/$total_steps] $1"
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
    --strict-health)
      STRICT_HEALTH="true"
      shift
      ;;
    --skip-auth-rate-limit)
      CHECK_AUTH_RATE_LIMIT="false"
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

if [[ -z "$EMAIL" ]]; then
  EMAIL="qa-security-e2e-$(date +%s)-$RANDOM@example.com"
fi

if is_true "$CHECK_AUTH_RATE_LIMIT"; then
  total_steps=11
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

step "Health probe (informational)"
health_file="$tmp_dir/health.json"
health_code="$(request GET "$BASE_URL/actuator/health" "$health_file")"
if is_true "$STRICT_HEALTH"; then
  [[ "$health_code" == "200" || "$health_code" == "503" ]] \
    || fail "/actuator/health expected 200 or 503, got $health_code"
fi
echo "      /actuator/health status=$health_code"

step "Security headers check"
headers_file="$tmp_dir/headers.txt"
curl -sSI "$BASE_URL/api/models" -o "$headers_file" || true
grep -qi "^x-content-type-options: nosniff" "$headers_file" || fail "Missing security header: X-Content-Type-Options"
grep -qi "^x-frame-options: DENY" "$headers_file" || fail "Missing security header: X-Frame-Options"
grep -qi "^content-security-policy: default-src 'self'" "$headers_file" || fail "Missing security header: Content-Security-Policy"
grep -qi "^strict-transport-security: max-age=31536000; includeSubDomains" "$headers_file" \
  || fail "Missing security header: Strict-Transport-Security"

step "Unauthenticated access denied"
unauth_models_file="$tmp_dir/unauth_models.json"
unauth_models_code="$(request GET "$BASE_URL/api/models" "$unauth_models_file")"
assert_status "$unauth_models_code" "401" "/api/models without token"

unauth_policy_file="$tmp_dir/unauth_policy.json"
unauth_policy_code="$(request PUT "$BASE_URL/api/tool-policy" "$unauth_policy_file" \
  -H "Content-Type: application/json" \
  --data-binary '{"enabled":true}')"
assert_status "$unauth_policy_code" "401" "/api/tool-policy without token"

step "Register/login security test user"
login_file="$tmp_dir/login.json"
login_code="$(login_with_credentials "$EMAIL" "$PASSWORD" "$login_file")"
if [[ "$login_code" == "200" ]]; then
  user_token="$(json_field "$login_file" ".token")"
else
  register_file="$tmp_dir/register.json"
  register_payload="$tmp_dir/register_payload.json"
  cat >"$register_payload" <<JSON
{"email":"$EMAIL","password":"$PASSWORD","name":"$NAME"}
JSON
  register_code="$(request POST "$BASE_URL/api/auth/register" "$register_file" \
    -H "Content-Type: application/json" \
    --data-binary "@$register_payload")"
  if [[ "$register_code" == "201" ]]; then
    user_token="$(json_field "$register_file" ".token")"
  elif [[ "$register_code" == "409" ]]; then
    login_code="$(login_with_credentials "$EMAIL" "$PASSWORD" "$login_file")"
    assert_status "$login_code" "200" "/api/auth/login after 409 register"
    user_token="$(json_field "$login_file" ".token")"
  elif [[ "$login_code" == "401" && ( "$register_code" == "401" || "$register_code" == "403" ) ]]; then
    fail "Login failed and self-registration is unavailable. Provide an existing account or enable registration."
  else
    fail "/api/auth/register expected 201 or 409, got $register_code"
  fi
fi
[[ -n "$user_token" ]] || fail "Failed to resolve test user token"

step "Authenticated baseline access"
auth_models_file="$tmp_dir/auth_models.json"
auth_models_code="$(request GET "$BASE_URL/api/models" "$auth_models_file" \
  -H "Authorization: Bearer $user_token" \
  -H "X-Tenant-Id: $TENANT_ID")"
assert_status "$auth_models_code" "200" "/api/models with valid user token"

step "RBAC check (USER blocked on admin paths)"
mcp_user_file="$tmp_dir/mcp_user.json"
mcp_user_code="$(request GET "$BASE_URL/api/mcp/servers" "$mcp_user_file" \
  -H "Authorization: Bearer $user_token")"
assert_status "$mcp_user_code" "403" "/api/mcp/servers with USER token"
mcp_user_error="$(json_field "$mcp_user_file" ".error")"
[[ -n "$mcp_user_error" ]] || fail "403 response should contain an error body"
if [[ -n "$ADMIN_TOKEN" ]]; then
  mcp_admin_file="$tmp_dir/mcp_admin.json"
  mcp_admin_code="$(request GET "$BASE_URL/api/mcp/servers" "$mcp_admin_file" \
    -H "Authorization: Bearer $ADMIN_TOKEN")"
  assert_status "$mcp_admin_code" "200" "/api/mcp/servers with ADMIN token"
fi

step "Tampered token rejection"
tampered_models_file="$tmp_dir/tampered_models.json"
tampered_models_code="$(request GET "$BASE_URL/api/models" "$tampered_models_file" \
  -H "Authorization: Bearer ${user_token}x")"
assert_status "$tampered_models_code" "401" "/api/models with tampered token"

step "Tenant mismatch fail-close on chat endpoint"
tenant_mismatch_file="$tmp_dir/tenant_mismatch.json"
tenant_mismatch_code="$(request POST "$BASE_URL/api/chat" "$tenant_mismatch_file" \
  -H "Authorization: Bearer $user_token" \
  -H "X-Tenant-Id: other-tenant" \
  -H "Content-Type: application/json" \
  --data-binary '{"message":"tenant mismatch probe"}')"
assert_status "$tenant_mismatch_code" "400" "/api/chat with mismatched tenant header"
tenant_error="$(json_field "$tenant_mismatch_file" ".error")"
[[ "$tenant_error" == *"Tenant header does not match resolved tenant context"* ]] \
  || fail "Expected tenant mismatch fail-close message, got: $tenant_error"

step "Guard oversized input rejection"
large_message="$(head -c 15000 /dev/zero | tr '\0' 'A')"
oversized_payload="$tmp_dir/oversized_payload.json"
cat >"$oversized_payload" <<JSON
{"message":"$large_message"}
JSON
guard_file="$tmp_dir/guard_reject.json"
guard_code="$(request POST "$BASE_URL/api/chat" "$guard_file" \
  -H "Authorization: Bearer $user_token" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "Content-Type: application/json" \
  --data-binary "@$oversized_payload")"
if [[ "$guard_code" == "400" ]]; then
  guard_error="$(json_field "$guard_file" ".error")"
  [[ "$guard_error" == *"Request rejected by guard"* || "$guard_error" == *"GUARD_REJECTED"* || \
    "$guard_error" == *"Boundary violation"* || "$guard_error" == *"input.max_chars"* ]] \
    || fail "Expected guard rejection message, got: $guard_error"
elif [[ "$guard_code" == "200" ]]; then
  guard_success="$(jq -r 'if has("success") then (.success | tostring) else "__missing__" end' "$guard_file" 2>/dev/null || true)"
  guard_error_message="$(json_field "$guard_file" ".errorMessage")"
  if [[ "$guard_success" != "__missing__" && "$guard_success" != "false" ]]; then
    fail "Oversized input must not succeed (expected success=false when field exists)"
  fi
  [[ "$guard_error_message" == *"Request rejected by guard"* || "$guard_error_message" == *"GUARD_REJECTED"* || \
    "$guard_error_message" == *"Boundary violation"* || "$guard_error_message" == *"input.max_chars"* ]] \
    || fail "Expected guard rejection errorMessage, got: $guard_error_message"
else
  fail "/api/chat oversized input expected 400 or guarded 200, got $guard_code"
fi

step "Logout revocation check"
logout_file="$tmp_dir/logout.json"
logout_code="$(request POST "$BASE_URL/api/auth/logout" "$logout_file" \
  -H "Authorization: Bearer $user_token")"
assert_status "$logout_code" "200" "/api/auth/logout"

post_logout_models_file="$tmp_dir/post_logout_models.json"
post_logout_models_code="$(request GET "$BASE_URL/api/models" "$post_logout_models_file" \
  -H "Authorization: Bearer $user_token" \
  -H "X-Tenant-Id: $TENANT_ID")"
assert_status "$post_logout_models_code" "401" "/api/models with revoked token"

if is_true "$CHECK_AUTH_RATE_LIMIT"; then
  step "Auth brute-force rate limit check"
  rate_limit_probe_file="$tmp_dir/rate_limit_probe.json"
  rate_limit_probe_payload="$tmp_dir/rate_limit_probe_payload.json"
  rate_limit_attempts=$((AUTH_RATE_LIMIT_PER_MINUTE + 1))
  cat >"$rate_limit_probe_payload" <<JSON
{"email":"no-such-user-security-probe@example.com","password":"invalid-pass"}
JSON
  rate_limit_codes=()
  for i in $(seq 1 "$rate_limit_attempts"); do
    code="$(request POST "$BASE_URL/api/auth/login" "$rate_limit_probe_file" \
      -H "Content-Type: application/json" \
      -H "X-Forwarded-For: 203.0.113.77" \
      --data-binary "@$rate_limit_probe_payload")"
    rate_limit_codes+=("$code")
    if [[ "$code" == "429" ]]; then
      rate_limit_error="$(json_field "$rate_limit_probe_file" ".error")"
      [[ "$rate_limit_error" == *"Too many authentication attempts"* ]] \
        || fail "Auth rate-limit response body is unexpected: $rate_limit_error"
      break
    fi
  done
  if [[ "${rate_limit_codes[*]}" != *"429"* ]]; then
    fail "Auth rate limit did not trigger after $rate_limit_attempts attempts (limit=$AUTH_RATE_LIMIT_PER_MINUTE, codes: ${rate_limit_codes[*]})"
  fi
fi

echo "Security E2E validation passed."
echo "Base URL: $BASE_URL"
echo "User: $EMAIL"
