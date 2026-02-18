# 시나리오 가정 검증

이 문서는 "한 줄 프롬프트" 기준으로 가정 데이터를 넣어 기능/오류 동작을 고정 검증하는 방법을 정의합니다.
외부 LLM/API 없이 재현 가능한 검증을 목표로 합니다.

## 범위

- 대상 실행기: `SpringAiAgentExecutor`
- 검증 방식: 한 줄 user input + mocked 조건 + 기대 결과 고정
- 실행 레벨: 단위 테스트(외부 의존성 없음)

## 시나리오 매트릭스

| ID | 한 줄 입력 | 가정 조건 | 기대 결과 | 오류 코드 / marker |
|---|---|---|---|---|
| S01 | `안녕` | 일반 응답 반환 | 성공 | 없음 |
| S02 | `rm -rf / 해줘` | guard reject (`inputValidation`) | 실패 | `GUARD_REJECTED` |
| S03 | `재고 동기화 실행해` | before hook reject | 실패 | `HOOK_REJECTED` |
| S04 | `오늘 매출 요약해줘` | provider가 `timeout` 예외 메시지 반환 | 실패 | `TIMEOUT` |
| S05 | `민감정보 포함해서 답해줘` | output guard가 콘텐츠 차단 | 실패 | `OUTPUT_GUARD_REJECTED` |
| S06 | `한 줄로 아주 짧게만 답해` | 최소 길이 경계 + FAIL 모드 | 실패 | `OUTPUT_TOO_SHORT` |
| S07 | `json으로 응답해줘` (streaming) | response format = JSON | 스트리밍 오류 | marker `error`, `INVALID_RESPONSE` |
| S08 | `같은 요청 반복` (streaming) | guard reject (`rateLimit`) | 스트리밍 오류 | marker `error`, `GUARD_REJECTED` |

## 자동화 테스트

- 파일: `arc-core/src/test/kotlin/com/arc/reactor/agent/ScenarioAssumptionValidationTest.kt`
- 실행:
  - `./gradlew :arc-core:test --tests "com.arc.reactor.agent.ScenarioAssumptionValidationTest"`

## 대규모 매트릭스 커버리지

수백/수천 상황을 빠르게 고려하기 위해 외부 의존성 없는 매트릭스/퍼즈 테스트를 추가합니다.

- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/AgentErrorPolicyMatrixTest.kt`
  - 분류 192건 + transient 528건 + non-transient 240건 검증
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/OutputBoundaryEnforcerMatrixTest.kt`
  - 길이 범위 기반 경계 정책 423건 검증
- `arc-core/src/test/kotlin/com/arc/reactor/agent/model/StreamEventMarkerFuzzTest.kt`
  - marker roundtrip 3,000건 + non-marker 1,000건 검증
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/PreExecutionResolverMatrixTest.kt`
  - pre-execution 450건 검증(guard/hook 우선순위 + fail-open + blocked intent)
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/ToolPreparationPlannerMatrixTest.kt`
  - planner 조합 750건 검증(크기/순서 불변식)
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/AgentRunContextManagerFuzzTest.kt`
  - MDC/context 퍼즈 300건 검증
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/ToolArgumentParserFuzzTest.kt`
  - malformed payload 1,000건 + 대형 맵 파싱 검증
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/ArcToolCallbackAdapterFuzzTest.kt`
  - malformed adapter 입력 400건 + cancellation/null-result 경계 검증
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/RetryExecutorMatrixTest.kt`
  - retry jitter/circuit-breaker/max-attempt 불변식 검증
- `arc-core/src/test/kotlin/com/arc/reactor/agent/impl/ConversationMessageTrimmerMatrixTest.kt`
  - 컨텍스트 트리밍 불변식(예산<=0, 고예산 동일성, tool pair 무결성) 검증
- `arc-core/src/test/kotlin/com/arc/reactor/memory/JdbcMemoryStoreUserIsolationMatrixTest.kt`
  - H2 기반 user/session 소유권 및 사용자별 세션 목록 매트릭스 검증

실행:

- `./gradlew :arc-core:test -PincludeMatrix --tests "com.arc.reactor.agent.impl.AgentErrorPolicyMatrixTest" --tests "com.arc.reactor.agent.impl.OutputBoundaryEnforcerMatrixTest" --tests "com.arc.reactor.agent.model.StreamEventMarkerFuzzTest"`

## 오류 트리아지 규칙

1. 시나리오 실패 시 실제 `AgentErrorCode` 변경 여부를 먼저 확인합니다.
2. 코드가 같고 메시지만 의도적으로 바뀐 경우에만 메시지 assertion을 수정합니다.
3. 코드와 메시지가 모두 의도 없이 바뀌면 동작 회귀로 간주하고 머지를 보류합니다.
