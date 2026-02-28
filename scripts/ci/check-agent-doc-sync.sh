#!/usr/bin/env bash
# check-agent-doc-sync.sh
# Verifies that AGENTS.md contains the critical sections that must stay
# in sync with CLAUDE.md. AGENTS.md is intentionally shorter (Codex-first
# format) and is NOT required to be byte-identical to CLAUDE.md.
set -euo pipefail

fail=0

check_contains() {
  local file="$1"
  local pattern="$2"
  local label="$3"
  if ! grep -qF "$pattern" "$file"; then
    echo "::error file=$file::Missing required content in $file: $label"
    fail=1
  fi
}

for f in AGENTS.md CLAUDE.md; do
  if [[ ! -f "$f" ]]; then
    echo "::error file=$f::$f is missing"
    exit 1
  fi
done

# AGENTS.md must contain all critical gotcha topics (subset check)
check_contains AGENTS.md "CancellationException" "CancellationException gotcha"
check_contains AGENTS.md "maxToolCalls"           "ReAct infinite loop gotcha"
check_contains AGENTS.md ".forEach"               ".forEach coroutine gotcha"
check_contains AGENTS.md "Message pair"           "Message pair integrity gotcha"
check_contains AGENTS.md "Context trimming"       "Context trimming gotcha"
check_contains AGENTS.md "AssistantMessage"       "AssistantMessage constructor gotcha"
check_contains AGENTS.md "application.yml"        "API key env var gotcha"
check_contains AGENTS.md "mcp/servers"            "MCP registration gotcha"
check_contains AGENTS.md "anonymous"              "Guard null userId gotcha"
check_contains AGENTS.md "ChatOptions"            "Spring AI mock chain gotcha"
check_contains AGENTS.md "toolsUsed"              "toolsUsed append guard gotcha"

# AGENTS.md must have validate commands
check_contains AGENTS.md "compileKotlin"          "validate compile command"
check_contains AGENTS.md "gradlew test"           "validate test command"

# CLAUDE.md must still have all the same gotcha content
check_contains CLAUDE.md "CancellationException" "CancellationException gotcha"
check_contains CLAUDE.md "OUTPUT_GUARD_REJECTED"  "OUTPUT_GUARD_REJECTED error code"
check_contains CLAUDE.md "OUTPUT_TOO_SHORT"       "OUTPUT_TOO_SHORT error code"
check_contains CLAUDE.md "ConversationManager"    "ConversationManager key file"
check_contains CLAUDE.md "ErrorResponse"          "ErrorResponse standard DTO"

if [[ $fail -ne 0 ]]; then
  echo "::error::Agent instruction sync check FAILED — see errors above"
  exit 1
fi

echo "Agent instruction sync check passed."
