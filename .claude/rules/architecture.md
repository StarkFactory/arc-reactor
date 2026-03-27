# Architecture Rules

## 요청 흐름

```
Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response
```

**Guard = fail-close** (실패 = 차단). **Hook = fail-open** (실패 = 로깅 후 계속). 보안 로직은 반드시 Guard에만.

## 설계 원칙

1. **도구는 API** — LLM이 호출하는 전제. 설명에 목적·형식·경계·차이 명시
2. **루프 탈출 필수** — `maxToolCalls` + `withTimeout`
3. **Guard/Hook 분리** — 인증·인가 → Guard. 로깅·메트릭 → Hook
4. **메시지 쌍 무결성** — AssistantMessage + ToolResponseMessage 항상 쌍
5. **환경 근거** — 매 단계 도구 결과를 컨텍스트에 포함. 추론만 연쇄 → 환각 누적
6. **도구 출력 불신** — `ToolOutputSanitizer` + Output Guard
7. **동시성 존중** — `CancellationException` 먼저 catch & rethrow
8. **관측 가능성** — `AgentMetrics` + `ArcReactorTracer`로 매 단계 추적

## Anti-Patterns

| 패턴 | 대안 |
|------|------|
| 종료 조건 없는 루프 | `maxToolCalls` + `withTimeout` |
| 프롬프트 의존 보안 | Guard fail-close + `ToolApprovalPolicy` |
| 전지적 컨텍스트 | `TokenEstimator` + rerank + 트리밍 |
| 조용한 실패 | `"Error: {원인}"` + 메트릭 |
| 환각 도구 호출 | `toolsUsed` 어댑터 확인 |

## 확장 포인트

| Component | 규칙 | 실패 정책 |
|-----------|------|----------|
| **ToolCallback** | `"Error: ..."` 문자열 반환, throw 금지 | LLM이 대안 탐색 |
| **GuardStage** | 내장 순서 1–5, 커스텀 10+ | fail-close |
| **Hook** | try-catch 필수, `CancellationException` rethrow | fail-open |
| **Bean** | `@ConditionalOnMissingBean` 필수 | JDBC는 `@Primary` |
