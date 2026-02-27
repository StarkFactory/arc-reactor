# Arc Reactor — Agent Instructions

Spring AI-based AI Agent framework (Kotlin/Spring Boot). See CLAUDE.md for full context.

## Validate Commands

```bash
./gradlew compileKotlin compileTestKotlin   # Must produce 0 warnings
./gradlew test                              # Must pass before any PR
./gradlew test --tests "*.YourTestClass"    # Run single test during iteration
```

CI merge gate: `build`, `integration`, `docker` checks must all be green.

## Architecture at a Glance

Request flow: **Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response**

- `SpringAiAgentExecutor.kt` — Core loop (~1,060 lines). Touch with caution
- `ArcReactorAutoConfiguration.kt` — All beans. Override via `@ConditionalOnMissingBean`
- `AgentPolicyAndFeatureProperties.kt` — All `arc.reactor.*` config properties

**Guard = fail-close** (blocked). **Hook = fail-open** (log & continue). Security logic in Guard only.

## Critical Gotchas (bugs waiting to happen)

1. **CancellationException**: in every `suspend fun`, catch and rethrow `CancellationException` BEFORE any generic `catch (e: Exception)`. Failure breaks structured concurrency silently
2. **ReAct infinite loop**: when `maxToolCalls` is reached, set `activeTools = emptyList()`. Logging only causes an infinite loop
3. **.forEach in coroutines**: use `for (item in list)` not `list.forEach {}`. The lambda is not suspend-capable
4. **Message pair integrity**: `AssistantMessage(toolCalls)` and `ToolResponseMessage` must always be added/removed as a pair
5. **Context trimming**: Phase 2 guard condition is `>` not `>=`. Off-by-one drops the last UserMessage
6. **AssistantMessage constructor**: it is protected — use `AssistantMessage.builder().content().toolCalls().build()`
7. **API key env vars**: NEVER set provider keys with empty string defaults in `application.yml`. Use only `GEMINI_API_KEY`, `SPRING_AI_OPENAI_API_KEY`, `SPRING_AI_ANTHROPIC_API_KEY`
8. **MCP servers**: registered via REST API only (`POST /api/mcp/servers`). Do NOT hardcode in `application.yml`
9. **Guard null userId**: always fall back to `"anonymous"`. Skipping the guard is a security vulnerability
10. **Spring AI mock chain**: explicitly mock `.options(any<ChatOptions>())` in streaming tests
11. **toolsUsed list**: only append a tool name after confirming its adapter exists. Appending LLM-hallucinated names pollutes the response metadata

## Code Rules

- All interfaces are coroutine-based (`suspend fun`). No blocking calls except `ArcToolCallbackAdapter` (Spring AI constraint)
- Method ≤20 lines, line ≤120 chars. English-only comments and KDoc
- Logging: `private val logger = KotlinLogging.logger {}` at file top-level, before the class
- All controllers need `@Tag`. All endpoints need `@Operation(summary = "...")`
- Admin auth: use `AdminAuthSupport.isAdmin(exchange)` and `forbiddenResponse()` — do NOT duplicate

## Extension Points

| Component | Rule |
|-----------|------|
| ToolCallback | Return `"Error: ..."` strings — never throw |
| GuardStage | Built-in use orders 1–5. Custom: start at 10+ |
| Hook | Wrap in try-catch. Always rethrow `CancellationException` |
| Bean | `@ConditionalOnMissingBean` required. `ObjectProvider<T>` for optional deps |

## Testing Rules

- Every new feature needs tests. Every bug fix needs a regression test
- Use `AgentTestFixture`: `mockCallResponse()`, `mockToolCallResponse()`, `mockFinalResponse()`, `TrackingTool`
- Use `AgentResultAssertions`: `assertSuccess()`, `assertFailure()`, `assertErrorCode()`, `assertErrorContains()`
- ALL assertions must have failure messages — no bare `assertTrue(x)`
- `coEvery`/`coVerify` for suspend mocks. Prefer `runTest` over `runBlocking`

## PR Rules

- Include cost impact notes for any PR that adds LLM calls
- Patch/minor dep upgrades: merge after green CI. Major: migration notes + rollback plan required
- Spring Boot major upgrades are blocked without explicit maintainer approval
