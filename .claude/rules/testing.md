---
paths:
  - "*/src/test/**/*.kt"
---

# Testing Rules

## Framework

- JUnit 5 + MockK + Kotest assertions only (Kotest runner 사용 금지)
- `runTest { }` for 코루틴 테스트 (`kotlinx-coroutines-test`)
- suspend mock: `coEvery`/`coVerify`

## Shared Fixtures

- `AgentTestFixture` — ChatClient mock setup
  - `mockCallResponse(content)` — 단순 성공
  - `mockCallWithToolCalls(toolName, args, finalContent)` — 도구 호출 → 최종 응답
  - `mockStreamResponse(chunks)` — 스트리밍 청크
  - `TrackingTool` — 도구 호출 추적 spy
- `AgentResultAssertions` — 결과 검증
  - `assertSuccess()`, `assertFailure()`, `assertErrorContains("text")`, `assertErrorCode(code)`

## 규칙

- **IMPORTANT: 모든 assertion에 실패 메시지 필수**: `assertTrue(x) { "Expected Y" }`
- `@Nested` inner class로 테스트 그룹화
- `assertInstanceOf<Type>(value)` 사용 (캐스트된 객체 반환)
- 스트리밍 테스트: `requestSpec.options(any<ChatOptions>())` 명시적 mock
- `returnsMany`로 ReAct 루프 순차 응답 설정

## 금지 패턴

- `assertTrue(x is Type)` → `assertInstanceOf<Type>(x)` 사용
- `System.currentTimeMillis()` → `AtomicInteger` 동시성 카운팅
- 메시지 없는 assertion (`assertTrue(x)`) → `assertTrue(x) { "reason" }`
- 동일 파일을 여러 에이전트가 동시 수정+테스트 금지 (flaky 원인)
