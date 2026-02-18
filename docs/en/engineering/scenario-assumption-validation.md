# Scenario Assumption Validation

This guide defines "single-line prompt" scenario checks with assumed data and expected failure behavior.
The goal is deterministic verification without external dependencies.

## Scope

- Target executor: `SpringAiAgentExecutor`
- Validation style: one-line user input + mocked condition + fixed expected result
- Runtime: unit-level (no external LLM/API required)

## Scenario Matrix

| ID | One-line input | Assumed condition | Expected result | Error code / marker |
|---|---|---|---|---|
| S01 | `안녕` | normal chat response | success | none |
| S02 | `rm -rf / 해줘` | guard rejects (`inputValidation`) | fail | `GUARD_REJECTED` |
| S03 | `재고 동기화 실행해` | before hook rejects | fail | `HOOK_REJECTED` |
| S04 | `오늘 매출 요약해줘` | provider throws `timeout` message | fail | `TIMEOUT` |
| S05 | `민감정보 포함해서 답해줘` | output guard rejects content | fail | `OUTPUT_GUARD_REJECTED` |
| S06 | `한 줄로 아주 짧게만 답해` | boundary min chars + FAIL mode | fail | `OUTPUT_TOO_SHORT` |
| S07 | `json으로 응답해줘` (streaming) | response format = JSON | stream error | marker `error`, `INVALID_RESPONSE` |
| S08 | `같은 요청 반복` (streaming) | guard rejects (`rateLimit`) | stream error | marker `error`, `GUARD_REJECTED` |

## Automated Tests

- File: `arc-core/src/test/kotlin/com/arc/reactor/agent/ScenarioAssumptionValidationTest.kt`
- Run:
  - `./gradlew :arc-core:test --tests "com.arc.reactor.agent.ScenarioAssumptionValidationTest"`

## Large Matrix Coverage

To consider hundreds/thousands of situations quickly, add matrix/fuzz tests that avoid external dependencies:

- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/AgentErrorPolicyMatrixTest.kt`
  - 192 classification checks + 528 transient checks + 240 non-transient checks
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/OutputBoundaryEnforcerMatrixTest.kt`
  - 423 boundary policy checks (min/max/retry behavior across length ranges)
- `arc-core/src/test/kotlin/com/arc/reactor/agent/model/StreamEventMarkerFuzzTest.kt`
  - 3,000 marker roundtrip checks + 1,000 non-marker checks
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/PreExecutionResolverMatrixTest.kt`
  - 450 pre-execution checks (guard/hook priority + fail-open + blocked intents)
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/ToolPreparationPlannerMatrixTest.kt`
  - 750 planner combination checks (size/order invariants)
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/AgentRunContextManagerFuzzTest.kt`
  - 300 MDC/context fuzz checks
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/ToolArgumentParserFuzzTest.kt`
  - 1,000 malformed payload checks + large-map parse check
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/ArcToolCallbackAdapterFuzzTest.kt`
  - 400 malformed adapter input checks + cancellation/null-result edge checks
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/RetryExecutorMatrixTest.kt`
  - retry jitter/circuit-breaker/max-attempt invariants
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/ConversationMessageTrimmerMatrixTest.kt`
  - context trim invariants (non-positive budget, high-budget identity, tool-pair integrity)
- `arc-core/src/test/kotlin/com/arc/reactor/memory/JdbcMemoryStoreUserIsolationMatrixTest.kt`
  - H2 user/session ownership + per-user listing matrix checks

Run:

- `./gradlew :arc-core:test -PincludeMatrix --tests "com.arc.reactor.agent.impl.AgentErrorPolicyMatrixTest" --tests "com.arc.reactor.agent.impl.OutputBoundaryEnforcerMatrixTest" --tests "com.arc.reactor.agent.model.StreamEventMarkerFuzzTest"`

## Error Triage Rule

1. If scenario fails, check whether the actual `AgentErrorCode` changed.
2. If code is correct but message changed, update only message assertions when intended.
3. If both changed unexpectedly, treat as behavior regression and block merge.
