---
paths:
  - "src/test/**/*.kt"
---

# Testing Rules

## Framework

- JUnit 5 (`org.junit.jupiter.api.Assertions.*`) + MockK + Kotest assertions only
- Kotest: matchers only (`shouldBe`, `shouldContain`). Do not use Kotest runner
- `runTest { }` for coroutine tests (from `kotlinx-coroutines-test`)

## Shared Fixtures

- `AgentTestFixture` — provides ChatClient, RequestSpec, CallResponseSpec, StreamResponseSpec mock setup
  - `mockCallResponse(content)` — simple success response
  - `mockCallWithToolCalls(toolName, args, finalContent)` — tool call → final response
  - `mockStreamResponse(chunks)` — streaming chunk response
  - `TrackingTool` — spy for verifying tool invocations
- `AgentResultAssertions` — extension functions:
  - `result.assertSuccess()` — prints errorMessage on failure
  - `result.assertFailure()` — prints content on unexpected success
  - `result.assertErrorContains("text")` — partial error message matching
  - `result.assertErrorCode(AgentErrorCode.RATE_LIMITED)` — error code assertion

## Conventions

- All assertions must have failure messages: `assertTrue(condition) { "Expected X but got Y" }`
- `@Nested` inner classes for test grouping (e.g., `inner class WhenGuardRejects`)
- Use `assertInstanceOf<Type>(value)` — returns cast object for subsequent assertions
- Streaming tests must mock `requestSpec.options(any<ChatOptions>())`
- Use `returnsMany` for sequential ReAct loop response setup

## Anti-patterns

- Do not use `assertTrue(x is Type)` → use `assertInstanceOf<Type>(x)` instead
- Do not use `System.currentTimeMillis()` for timing tests → use `AtomicInteger` concurrency counting
- Do not use bare assertions (`assertTrue(x)`, `assertNotNull(y)`) → always include failure message
- Do not run multiple agents modifying the same files and testing simultaneously (causes flaky tests)
