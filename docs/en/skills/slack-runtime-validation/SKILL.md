# Shared Skill: Slack Runtime Validation

This workflow is tool-agnostic and intended for both Claude Code and Codex.

## Goal

Validate Slack-first production paths end-to-end before release, including input parsing, signature guard, agent execution, and downstream integrations.

## When To Use

- Slack gateway/controller/handler changes
- Guard, Hook, RAG, MCP, or ReAct loop behavior changes that affect Slack traffic
- Incident triage for Slack command/event failures
- Pre-release regression checks for Slack-enabled deployments

## Validation Scope

1. Request authenticity: Slack signature/timestamp verification.
2. Input binding: slash command and event payload parsing.
3. Guard pipeline: input validation, rate limit, and rejection behavior.
4. Agent execution: ReAct tool loop and error-code mapping.
5. Integration paths: RAG retrieval and MCP tool availability.
6. Response delivery: channel post, thread reply, and response_url fallback.
7. Observability: structured logs and error body consistency.

## Scenario Matrix

| Scenario | Input/Condition | Expected Result | Verification |
|---|---|---|---|
| Slash happy path | valid slash payload + valid signature | 200/ack and downstream response | `DefaultSlackCommandHandlerTest` + controller test |
| Event happy path | mention/event payload + valid signature | command routed and response sent | `DefaultSlackEventHandlerTest` + processor test |
| Signature reject | invalid signature or stale timestamp | request blocked (401/403) | `SlackSignatureWebFilterTest` |
| Guard reject | disallowed/oversized input | fail-close with guard error | `ScenarioAssumptionValidationTest` + slack handler tests |
| Tool failure | tool execution throws/fails | graceful error mapping/fallback | slack handler + agent tests |
| RAG disabled/enabled | same prompt under both flags | behavior matches feature toggle policy | web/core integration tests |
| MCP unavailable | MCP server disconnected | deterministic tool error path, no crash | MCP integration/unit tests |
| Response fallback | channel post fails | response_url fallback used | `DefaultSlackCommandHandlerTest` |

## Command Sequence

```bash
# 1) Fast Slack slice
./gradlew :arc-slack:test --tests "*SlackSignatureWebFilterTest" --tests "*DefaultSlackCommandHandlerTest" --tests "*DefaultSlackEventHandlerTest" --tests "*SlackUserJourneyScenarioTest"

# 2) Core behavior slice (guard/react/tool)
./gradlew :arc-core:test --tests "*ScenarioAssumptionValidationTest" --tests "*ToolCallOrchestratorTest" --tests "*StreamingReActLoopExecutorTest"

# 3) Web/API validation slice for MCP/RAG request paths
./gradlew :arc-web:test --tests "*McpServerRequestValidationTest" --tests "*ToolPolicyIntegrationTest" --tests "*RagIngestionIntegrationTest"

# 4) Full regression
./gradlew test

# 5) Live runtime path validation (requires running app + Slack env vars)
BASE_URL=http://localhost:18084 scripts/dev/validate-slack-runtime.sh
```

## Triage Checklist

- Confirm request reached `SlackSignatureWebFilter` and passed verification.
- Confirm parsed Slack payload contains expected command/event fields.
- Confirm guard rejection reason and `AgentErrorCode` mapping are deterministic.
- Confirm fallback behavior (response_url / fail-open vs fail-close) matches policy.
- Confirm no ownership/auth bypass in admin/write endpoints touched by flow.

## Definition Of Done

- Slack happy paths and rejection paths are both covered by automated tests.
- Guard and error semantics are consistent between Slack and web entrypoints.
- MCP/RAG toggle behavior is verified for affected scenarios.
- Full regression (`./gradlew test`) passes.
