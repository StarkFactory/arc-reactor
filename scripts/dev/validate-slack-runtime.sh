#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18084}"
TENANT_ID="${TENANT_ID:-default}"
SLACK_SIGNING_SECRET="${SLACK_SIGNING_SECRET:-}"
SLACK_BOT_TOKEN="${SLACK_BOT_TOKEN:-}"
SLACK_CHANNEL_ID="${SLACK_CHANNEL_ID:-}"
SLACK_TEST_USER_ID="${SLACK_TEST_USER_ID:-U0A8VU1K4F5}"
SLASH_COMMAND="${SLASH_COMMAND:-/jarvis}"
RAG_CODE="${RAG_CODE:-ORBIT-LIME-9721}"
WAIT_SECONDS="${WAIT_SECONDS:-20}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-2}"
REQUIRE_MCP_CHECK="${REQUIRE_MCP_CHECK:-false}"
MCP_SERVER_NAME="${MCP_SERVER_NAME:-runtime-validation-mcp}"
MCP_SSE_URL="${MCP_SSE_URL:-}"
MCP_CONNECT_WAIT_SECONDS="${MCP_CONNECT_WAIT_SECONDS:-20}"
QA_EMAIL="${QA_EMAIL:-}"
QA_PASSWORD="${QA_PASSWORD:-passw0rd!}"
QA_NAME="${QA_NAME:-Slack Runtime QA}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"
ADMIN_EMAIL="${ADMIN_EMAIL:-}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
SLACK_HTTP_MODE_OVERRIDE="${SLACK_HTTP_MODE_OVERRIDE:-}"
SLACK_HTTP_MODE="unknown"
RAG_READY="false"
USER_TOKEN=""
EFFECTIVE_ADMIN_TOKEN=""

TMP_DIR="$(mktemp -d)"
RUN_ID="slack-runtime-$(date +%s)-$RANDOM"
trap 'rm -rf "$TMP_DIR"' EXIT

require_cmd() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    echo "Missing required command: $name" >&2
    exit 1
  fi
}

require_env() {
  local name="$1"
  if [[ -z "${!name}" ]]; then
    echo "Missing required environment variable: $name" >&2
    exit 1
  fi
}

print_step() {
  local message="$1"
  printf "\n==> %s\n" "$message"
}

assert_eq() {
  local actual="$1"
  local expected="$2"
  local message="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "Assertion failed: $message (actual=$actual, expected=$expected)" >&2
    exit 1
  fi
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local message="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    echo "Assertion failed: $message (needle=$needle)" >&2
    exit 1
  fi
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  local message="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    echo "Assertion failed: $message (needle=$needle)" >&2
    exit 1
  fi
}

is_true() {
  local value
  value="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  [[ "$value" == "1" || "$value" == "true" || "$value" == "yes" || "$value" == "y" ]]
}

json_field() {
  local file="$1"
  local query="$2"
  jq -r "$query" "$file"
}

register_or_login() {
  local email="$1"
  local password="$2"
  local name="$3"
  local register_req="$TMP_DIR/register_req_${RANDOM}.json"
  local register_resp="$TMP_DIR/register_resp_${RANDOM}.json"
  local login_req="$TMP_DIR/login_req_${RANDOM}.json"
  local login_resp="$TMP_DIR/login_resp_${RANDOM}.json"
  local code token

  cat >"$register_req" <<JSON
{"email":"$email","password":"$password","name":"$name"}
JSON
  code="$(curl -sS -o "$register_resp" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    --data @"$register_req" \
    "$BASE_URL/api/auth/register")"
  if [[ "$code" == "201" ]]; then
    token="$(json_field "$register_resp" '.token')"
    [[ -n "$token" && "$token" != "null" ]] || {
      echo "Register succeeded but token is missing for $email" >&2
      exit 1
    }
    printf '%s' "$token"
    return 0
  fi
  if [[ "$code" != "409" ]]; then
    echo "Register failed for $email (status=$code)" >&2
    cat "$register_resp" >&2
    exit 1
  fi

  cat >"$login_req" <<JSON
{"email":"$email","password":"$password"}
JSON
  code="$(curl -sS -o "$login_resp" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    --data @"$login_req" \
    "$BASE_URL/api/auth/login")"
  if [[ "$code" != "200" ]]; then
    echo "Login failed for $email after 409 register (status=$code)" >&2
    cat "$login_resp" >&2
    exit 1
  fi
  token="$(json_field "$login_resp" '.token')"
  [[ -n "$token" && "$token" != "null" ]] || {
    echo "Login succeeded but token is missing for $email" >&2
    exit 1
  }
  printf '%s' "$token"
}

resolve_auth_tokens() {
  if [[ -z "$QA_EMAIL" ]]; then
    QA_EMAIL="qa-slack-runtime-$RUN_ID@example.com"
  fi
  USER_TOKEN="$(register_or_login "$QA_EMAIL" "$QA_PASSWORD" "$QA_NAME")"

  if [[ -n "$ADMIN_TOKEN" ]]; then
    EFFECTIVE_ADMIN_TOKEN="$ADMIN_TOKEN"
    return 0
  fi

  if [[ -n "$ADMIN_EMAIL" && -n "$ADMIN_PASSWORD" ]]; then
    EFFECTIVE_ADMIN_TOKEN="$(register_or_login "$ADMIN_EMAIL" "$ADMIN_PASSWORD" "Slack Runtime Admin")"
    return 0
  fi

  EFFECTIVE_ADMIN_TOKEN=""
}

sign_body() {
  local timestamp="$1"
  local body="$2"
  local digest
  digest="$(printf 'v0:%s:%s' "$timestamp" "$body" \
    | openssl dgst -sha256 -hmac "$SLACK_SIGNING_SECRET" \
    | awk '{print $NF}')"
  printf 'v0=%s' "$digest"
}

post_signed_json() {
  local path="$1"
  local body="$2"
  local signature="$3"
  local timestamp="$4"
  local out="$5"
  curl -sS -o "$out" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    -H "X-Slack-Request-Timestamp: $timestamp" \
    -H "X-Slack-Signature: $signature" \
    --data "$body" \
    "$BASE_URL$path"
}

build_slash_form() {
  local text="$1"
  local out="$2"
  python3 - <<'PY' "$SLASH_COMMAND" "$text" "$SLACK_TEST_USER_ID" "$SLACK_CHANNEL_ID" "$out"
import sys
import urllib.parse

command, text, user_id, channel_id, out = sys.argv[1:]
payload = {
    "command": command,
    "text": text,
    "user_id": user_id,
    "user_name": "runtime-validator",
    "channel_id": channel_id,
    "channel_name": "runtime-validation",
    "response_url": "https://example.invalid/response",
    "trigger_id": "13345224609.738474920.8088930838d88f008e0",
}
with open(out, "w", encoding="utf-8") as f:
    f.write(urllib.parse.urlencode(payload))
PY
}

post_signed_form() {
  local path="$1"
  local body_file="$2"
  local signature="$3"
  local timestamp="$4"
  local out="$5"
  curl -sS -o "$out" -w "%{http_code}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -H "X-Slack-Request-Timestamp: $timestamp" \
    -H "X-Slack-Signature: $signature" \
    --data @"$body_file" \
    "$BASE_URL$path"
}

slack_api() {
  local endpoint="$1"
  local out="$2"
  curl -sS -o "$out" \
    -H "Authorization: Bearer $SLACK_BOT_TOKEN" \
    "https://slack.com/api/$endpoint"
}

detect_slack_http_mode() {
  if [[ -n "$SLACK_HTTP_MODE_OVERRIDE" ]]; then
    SLACK_HTTP_MODE="$SLACK_HTTP_MODE_OVERRIDE"
    echo "Detected Slack HTTP mode: $SLACK_HTTP_MODE (override)"
    return 0
  fi

  local probe_events="$TMP_DIR/probe_events.json"
  local probe_commands="$TMP_DIR/probe_commands.json"
  local events_status commands_status

  events_status="$(curl -sS -o "$probe_events" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    --data '{"type":"url_verification","challenge":"arc-probe"}' \
    "$BASE_URL/api/slack/events" || true)"
  commands_status="$(curl -sS -o "$probe_commands" -w "%{http_code}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data 'command=%2Fjarvis&text=probe&user_id=U_TEST&channel_id=C_TEST' \
    "$BASE_URL/api/slack/commands" || true)"

  if [[ "$events_status" == "404" && "$commands_status" == "404" ]]; then
    SLACK_HTTP_MODE="socket_mode"
  elif [[ "$events_status" == "401" && "$commands_status" == "401" && -z "$SLACK_SIGNING_SECRET" ]]; then
    SLACK_HTTP_MODE="socket_mode"
  else
    SLACK_HTTP_MODE="events_api"
  fi

  echo "Detected Slack HTTP mode: $SLACK_HTTP_MODE (events=$events_status, commands=$commands_status)"
}

find_connected_mcp_server_with_read_messages() {
  [[ -n "$EFFECTIVE_ADMIN_TOKEN" ]] || return 1

  local list_file="$TMP_DIR/mcp_servers_list.json"
  curl -sS -o "$list_file" \
    -H "Authorization: Bearer $EFFECTIVE_ADMIN_TOKEN" \
    -H "X-Tenant-Id: $TENANT_ID" \
    "$BASE_URL/api/mcp/servers"

  if ! jq -e 'type == "array"' "$list_file" >/dev/null 2>&1; then
    return 1
  fi

  local names
  names="$(jq -r '.[].name // empty' "$list_file")"
  if [[ -z "$names" ]]; then
    return 1
  fi

  local name
  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    local detail_file="$TMP_DIR/mcp_detail_${name}.json"
    local status
    status="$(curl -sS -o "$detail_file" -w "%{http_code}" \
      -H "Authorization: Bearer $EFFECTIVE_ADMIN_TOKEN" \
      -H "X-Tenant-Id: $TENANT_ID" \
      "$BASE_URL/api/mcp/servers/$name")"
    [[ "$status" != "200" ]] && continue

    local connected
    connected="$(jq -r '.status == "CONNECTED"' "$detail_file")"
    local has_tool
    has_tool="$(jq -r '(.tools // []) | index("read_messages") != null' "$detail_file")"
    if [[ "$connected" == "true" && "$has_tool" == "true" ]]; then
      printf '%s' "$name"
      return 0
    fi
  done <<< "$names"

  return 1
}

register_mcp_server_if_requested() {
  if [[ -z "$MCP_SSE_URL" ]]; then
    return 0
  fi
  [[ -n "$EFFECTIVE_ADMIN_TOKEN" ]] || {
    echo "REQUIRE_MCP_CHECK requires ADMIN_TOKEN (or ADMIN_EMAIL/ADMIN_PASSWORD)." >&2
    exit 1
  }

  local req_file="$TMP_DIR/mcp_register_req.json"
  cat >"$req_file" <<JSON
{"name":"$MCP_SERVER_NAME","transportType":"SSE","config":{"url":"$MCP_SSE_URL"},"autoConnect":true}
JSON

  local resp_file="$TMP_DIR/mcp_register_resp.json"
  local status
  status="$(curl -sS -o "$resp_file" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $EFFECTIVE_ADMIN_TOKEN" \
    -H "X-Tenant-Id: $TENANT_ID" \
    --data @"$req_file" \
    "$BASE_URL/api/mcp/servers")"
  if [[ "$status" != "201" && "$status" != "409" ]]; then
    echo "Failed to register MCP server '$MCP_SERVER_NAME' (status=$status)" >&2
    cat "$resp_file" >&2
    exit 1
  fi

  local connect_resp="$TMP_DIR/mcp_connect_resp.json"
  status="$(curl -sS -o "$connect_resp" -w "%{http_code}" \
    -X POST \
    -H "Authorization: Bearer $EFFECTIVE_ADMIN_TOKEN" \
    -H "X-Tenant-Id: $TENANT_ID" \
    "$BASE_URL/api/mcp/servers/$MCP_SERVER_NAME/connect")"
  if [[ "$status" != "200" && "$status" != "503" ]]; then
    echo "Unexpected MCP connect response for '$MCP_SERVER_NAME' (status=$status)" >&2
    cat "$connect_resp" >&2
    exit 1
  fi
}

ensure_mcp_ready() {
  if ! is_true "$REQUIRE_MCP_CHECK"; then
    return 0
  fi

  register_mcp_server_if_requested

  local waited=0
  while (( waited < MCP_CONNECT_WAIT_SECONDS )); do
    local found
    found="$(find_connected_mcp_server_with_read_messages || true)"
    if [[ -n "$found" ]]; then
      MCP_SERVER_NAME="$found"
      return 0
    fi
    sleep "$POLL_INTERVAL_SECONDS"
    waited=$((waited + POLL_INTERVAL_SECONDS))
  done

  echo "No CONNECTED MCP server with read_messages tool found." >&2
  echo "Set MCP_SSE_URL to auto-register a server or disable with REQUIRE_MCP_CHECK=false." >&2
  exit 1
}

wait_for_question_ts() {
  local prompt="$1"
  local waited=0
  local history="$TMP_DIR/slack_history_${RANDOM}.json"

  while (( waited < WAIT_SECONDS )); do
    slack_api "conversations.history?channel=$SLACK_CHANNEL_ID&limit=40" "$history"
    local ok
    ok="$(json_field "$history" '.ok')"
    assert_eq "$ok" "true" "Slack conversations.history must succeed"

    local ts
    ts="$(jq -r --arg prompt "$prompt" \
      '.messages[] | select((.text // "") | contains($prompt)) | .ts' \
      "$history" \
      | head -n1)"
    if [[ -n "$ts" ]]; then
      printf '%s' "$ts"
      return 0
    fi

    sleep "$POLL_INTERVAL_SECONDS"
    waited=$((waited + POLL_INTERVAL_SECONDS))
  done

  return 1
}

wait_for_thread_reply() {
  local question_ts="$1"
  local waited=0
  local reply_file="$TMP_DIR/slack_replies_${RANDOM}.json"

  while (( waited < WAIT_SECONDS )); do
    slack_api "conversations.replies?channel=$SLACK_CHANNEL_ID&ts=$question_ts&limit=10" "$reply_file"
    local ok
    ok="$(json_field "$reply_file" '.ok')"
    assert_eq "$ok" "true" "Slack conversations.replies must succeed"

    local count
    count="$(json_field "$reply_file" '.messages | length')"
    if (( count >= 2 )); then
      jq -r '.messages[1].text // ""' "$reply_file"
      return 0
    fi

    sleep "$POLL_INTERVAL_SECONDS"
    waited=$((waited + POLL_INTERVAL_SECONDS))
  done

  echo "Timed out waiting for Slack thread reply for ts=$question_ts" >&2
  echo "Last replies payload:" >&2
  cat "$reply_file" >&2
  exit 1
}

send_signed_slash_and_get_reply() {
  local prompt="$1"
  local tag="$2"

  local form_file="$TMP_DIR/${tag}_form.txt"
  build_slash_form "$prompt" "$form_file"
  local form_body
  form_body="$(cat "$form_file")"

  local ts
  ts="$(date +%s)"
  local sig
  sig="$(sign_body "$ts" "$form_body")"

  local ack_file="$TMP_DIR/${tag}_ack.json"
  local status
  status="$(post_signed_form "/api/slack/commands" "$form_file" "$sig" "$ts" "$ack_file")"
  assert_eq "$status" "200" "Signed slash command must return 200"
  local ack_text
  ack_text="$(json_field "$ack_file" '.text')"
  assert_contains "$ack_text" "Processing" "Slash ack must indicate processing"

  local question_ts
  question_ts="$(wait_for_question_ts "$prompt" || true)"
  if [[ -z "$question_ts" ]]; then
    echo "Failed to find question message in Slack history for prompt: $prompt" >&2
    exit 1
  fi

  wait_for_thread_reply "$question_ts"
}

check_health() {
  local health_file="$TMP_DIR/health.json"
  local status_code
  status_code="$(curl -sS -o "$health_file" -w "%{http_code}" "$BASE_URL/actuator/health")"
  if [[ "$status_code" != "200" && "$status_code" != "503" ]]; then
    echo "Unexpected /actuator/health status code: $status_code" >&2
    exit 1
  fi
  local status
  status="$(json_field "$health_file" '.status')"
  if [[ "$status" == "UP" ]]; then
    return 0
  fi

  local liveness_file="$TMP_DIR/liveness.json"
  local readiness_file="$TMP_DIR/readiness.json"
  local liveness_code readiness_code liveness_status readiness_status
  liveness_code="$(curl -sS -o "$liveness_file" -w "%{http_code}" "$BASE_URL/actuator/health/liveness")"
  readiness_code="$(curl -sS -o "$readiness_file" -w "%{http_code}" "$BASE_URL/actuator/health/readiness")"
  liveness_status="$(json_field "$liveness_file" '.status')"
  readiness_status="$(json_field "$readiness_file" '.status')"
  if [[ "$liveness_code" == "200" && "$readiness_code" == "200" && "$liveness_status" == "UP" && "$readiness_status" == "UP" ]]; then
    return 0
  fi
  echo "Application health must be UP (or probes UP). root=$status liveness=$liveness_status readiness=$readiness_status" >&2
  exit 1
}

check_signature_filter() {
  local body='{"type":"url_verification","challenge":"arc-check"}'
  local ts sig status
  ts="$(date +%s)"
  sig="$(sign_body "$ts" "$body")"

  local invalid_file="$TMP_DIR/sig_invalid.json"
  status="$(post_signed_json "/api/slack/events" "$body" "v0=invalidsignature" "$ts" "$invalid_file")"
  assert_eq "$status" "403" "Invalid Slack signature must be rejected"
  assert_contains "$(cat "$invalid_file")" "Signature mismatch" "Reject reason must mention signature mismatch"

  local valid_file="$TMP_DIR/sig_valid.json"
  status="$(post_signed_json "/api/slack/events" "$body" "$sig" "$ts" "$valid_file")"
  assert_eq "$status" "200" "Valid Slack signature must pass"
  local challenge
  challenge="$(json_field "$valid_file" '.challenge')"
  assert_eq "$challenge" "arc-check" "Valid signature must return challenge payload"
}

check_invalid_slash_payload() {
  local body='command=%2Fjarvis&text=hello&user_id=U_TEST&channel_id=C_TEST'
  local ts sig status
  ts="$(date +%s)"
  sig="$(sign_body "$ts" "$body")"

  local out="$TMP_DIR/slash_invalid.json"
  status="$(curl -sS -o "$out" -w "%{http_code}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -H "X-Slack-Request-Timestamp: $ts" \
    -H "X-Slack-Signature: $sig" \
    --data "$body" \
    "$BASE_URL/api/slack/commands")"

  assert_eq "$status" "400" "Invalid slash payload must return 400"
  assert_contains "$(cat "$out")" "Invalid slash command payload" "Invalid slash payload message must be explicit"
}

check_guard_fail_close() {
  local req="$TMP_DIR/chat_guard_req.json"
  local resp="$TMP_DIR/chat_guard_resp.json"

  python3 - <<'PY' "$req"
import json
import sys

payload = {
    "message": "x" * 10050,
    "userId": "guard-test-user",
    "metadata": {
        "source": "slack",
        "channel": "slack",
        "sessionId": "guard-fail-close-check"
    }
}
with open(sys.argv[1], "w", encoding="utf-8") as f:
    json.dump(payload, f)
PY

  local status
  status="$(curl -sS -o "$resp" -w "%{http_code}" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H "X-Tenant-Id: $TENANT_ID" \
    -H "Content-Type: application/json" \
    --data @"$req" \
    "$BASE_URL/api/chat")"
  assert_eq "$status" "200" "Chat endpoint should return structured failure body"
  local success
  success="$(json_field "$resp" '.success')"
  assert_eq "$success" "false" "Guard violation must fail closed"
  assert_contains "$(json_field "$resp" '.errorMessage')" \
    "Boundary violation [input.max_chars]" \
    "Guard violation reason must be explicit"
}

check_rag_ingestion_and_retrieval() {
  if [[ -z "$EFFECTIVE_ADMIN_TOKEN" ]]; then
    echo "Skipping RAG checks: admin token is not available."
    RAG_READY="false"
    return 0
  fi

  local add_req="$TMP_DIR/rag_add_req.json"
  local add_resp="$TMP_DIR/rag_add_resp.json"
  local search_req="$TMP_DIR/rag_search_req.json"
  local search_resp="$TMP_DIR/rag_search_resp.json"

  cat >"$add_req" <<JSON
{"title":"Slack Runtime Validation $RUN_ID","content":"Slack runtime validation codename is $RAG_CODE. Run id: $RUN_ID"}
JSON

  local status
  status="$(curl -sS -o "$add_resp" -w "%{http_code}" \
    -H "Authorization: Bearer $EFFECTIVE_ADMIN_TOKEN" \
    -H "X-Tenant-Id: $TENANT_ID" \
    -H "Content-Type: application/json" \
    --data @"$add_req" \
    "$BASE_URL/api/documents")"
  if [[ "$status" == "404" ]]; then
    echo "Skipping RAG checks: /api/documents not available (rag.ingestion disabled?)"
    RAG_READY="false"
    return 0
  fi
  if [[ "$status" == "403" ]]; then
    echo "Skipping RAG checks: admin token is not authorized for /api/documents."
    RAG_READY="false"
    return 0
  fi
  assert_eq "$status" "201" "RAG document ingestion must return 201"

  cat >"$search_req" <<JSON
{"query":"codename $RAG_CODE $RUN_ID","topK":3}
JSON

  status="$(curl -sS -o "$search_resp" -w "%{http_code}" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H "X-Tenant-Id: $TENANT_ID" \
    -H "Content-Type: application/json" \
    --data @"$search_req" \
    "$BASE_URL/api/documents/search")"
  assert_eq "$status" "200" "RAG search must return 200"
  assert_contains "$(cat "$search_resp")" "$RAG_CODE" "RAG search response must include codename"
  RAG_READY="true"
}

check_react_mcp_via_chat_api() {
  local req="$TMP_DIR/chat_mcp_req.json"
  local resp="$TMP_DIR/chat_mcp_resp.json"

  cat >"$req" <<JSON
{
  "message": "Use the read_messages tool with channelId=$SLACK_CHANNEL_ID and limit=1. Summarize the result in one sentence.",
  "userId": "$SLACK_TEST_USER_ID",
  "metadata": {
    "source": "slack",
    "channel": "slack",
    "sessionId": "react-mcp-check-$RUN_ID"
  }
}
JSON

  local status
  status="$(curl -sS -o "$resp" -w "%{http_code}" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H "X-Tenant-Id: $TENANT_ID" \
    -H "Content-Type: application/json" \
    --data @"$req" \
    "$BASE_URL/api/chat")"
  assert_eq "$status" "200" "Chat API MCP check must return 200"
  local success
  success="$(json_field "$resp" '.success')"
  assert_eq "$success" "true" "MCP check should succeed"
  assert_contains "$(cat "$resp")" "read_messages" "MCP check must report read_messages in toolsUsed"
}

check_slack_rag_reply() {
  local prompt="[run:$RUN_ID] What is the runtime validation codename? Reply with codename only."
  local reply
  reply="$(send_signed_slash_and_get_reply "$prompt" "slash_rag")"
  assert_contains "$reply" "$RAG_CODE" "Slack thread RAG reply must contain codename"
}

check_slack_tool_reply() {
  local prompt="[run:$RUN_ID] Read 1 latest message from channel $SLACK_CHANNEL_ID and summarize it in one sentence."
  local reply
  reply="$(send_signed_slash_and_get_reply "$prompt" "slash_mcp")"
  assert_not_contains "$reply" ":warning: An unknown error occurred." \
    "Slack tool reply must not degrade to unknown error"
  assert_not_contains "$reply" "An unknown error occurred" \
    "Slack tool reply must include actionable content"
}

main() {
  require_cmd curl
  require_cmd jq
  require_cmd openssl
  require_cmd python3

  require_env SLACK_BOT_TOKEN
  require_env SLACK_CHANNEL_ID

  print_step "Detecting Slack HTTP mode"
  detect_slack_http_mode

  print_step "Resolving auth tokens"
  resolve_auth_tokens

  if [[ "$SLACK_HTTP_MODE" == "events_api" ]]; then
    require_env SLACK_SIGNING_SECRET
  fi

  print_step "Checking application health"
  check_health

  if [[ "$SLACK_HTTP_MODE" == "events_api" ]]; then
    print_step "Checking Slack signature guard (fail/pass)"
    check_signature_filter

    print_step "Checking slash payload validation"
    check_invalid_slash_payload
  else
    print_step "Skipping Slack HTTP signature/slash checks in socket_mode"
  fi

  print_step "Checking guard fail-close on oversized input"
  check_guard_fail_close

  print_step "Checking RAG document ingestion and retrieval"
  check_rag_ingestion_and_retrieval

  if is_true "$REQUIRE_MCP_CHECK"; then
    print_step "Ensuring MCP server is connected with read_messages tool"
    ensure_mcp_ready

    print_step "Checking ReAct + MCP via chat API (toolsUsed)"
    check_react_mcp_via_chat_api
  else
    print_step "Skipping MCP checks (REQUIRE_MCP_CHECK=false)"
  fi

  if [[ "$SLACK_HTTP_MODE" == "events_api" && "$RAG_READY" == "true" ]]; then
    print_step "Checking Slack slash path with RAG answer"
    check_slack_rag_reply
  else
    print_step "Skipping Slack slash RAG reply check (mode=$SLACK_HTTP_MODE rag_ready=$RAG_READY)"
  fi

  if [[ "$SLACK_HTTP_MODE" == "events_api" ]] && is_true "$REQUIRE_MCP_CHECK"; then
    print_step "Checking Slack slash path with tool-assisted answer"
    check_slack_tool_reply
  elif is_true "$REQUIRE_MCP_CHECK"; then
    print_step "Skipping Slack slash tool-assisted reply check in socket_mode"
  fi

  printf "\nPASS: Slack runtime validation completed successfully (run_id=%s)\n" "$RUN_ID"
}

main "$@"
