---
paths:
  - "src/test/**/*.kt"
---

# Testing Rules

## Framework

- JUnit 5 (`org.junit.jupiter.api.Assertions.*`) + MockK + Kotest assertions only
- Kotest: matcher만 사용 (`shouldBe`, `shouldContain`). Kotest runner는 사용하지 않음
- `runTest { }` for coroutine tests (from `kotlinx-coroutines-test`)

## Shared Fixtures

- `AgentTestFixture` — ChatClient, RequestSpec, CallResponseSpec, StreamResponseSpec mock 세팅 제공
  - `mockCallResponse(content)` — 단순 성공 응답
  - `mockCallWithToolCalls(toolName, args, finalContent)` — 도구 호출 → 최종 응답
  - `mockStreamResponse(chunks)` — 스트리밍 청크 응답
  - `TrackingTool` — 도구 호출 검증용 spy
- `AgentResultAssertions` — 확장 함수:
  - `result.assertSuccess()` — 실패 시 errorMessage 출력
  - `result.assertFailure()` — 성공 시 content 출력
  - `result.assertErrorContains("text")` — 에러 메시지 부분 매칭
  - `result.assertErrorCode(AgentErrorCode.RATE_LIMITED)` — 에러 코드 검증

## Conventions

- 모든 assertion에 실패 메시지 포함: `assertTrue(condition) { "Expected X but got Y" }`
- `@Nested` inner class로 테스트 그룹핑 (예: `inner class WhenGuardRejects`)
- `assertInstanceOf<Type>(value)` 사용 — cast된 객체 반환하므로 이후 검증에 사용 가능
- 스트리밍 테스트에서 `requestSpec.options(any<ChatOptions>())` mock 필수
- `returnsMany`로 ReAct 루프 순차 응답 세팅

## Anti-patterns

- `assertTrue(x is Type)` 사용 금지 → `assertInstanceOf<Type>(x)` 사용
- `System.currentTimeMillis()` 기반 타이밍 테스트 금지 → `AtomicInteger` 동시성 카운팅 사용
- bare assertion (`assertTrue(x)`, `assertNotNull(y)`) 금지 → 메시지 필수
- 여러 agent가 같은 파일 수정하면서 동시 테스트 금지 (flaky)
