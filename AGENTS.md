# Arc Reactor — Agent Instructions

Spring AI-based AI Agent framework (Kotlin/Spring Boot).
Provides a ReAct loop, Guard pipeline, Hook lifecycle, MCP integration, and multi-agent orchestration as a drop-in Spring Boot autoconfiguration.

## Environment Setup

Required env vars to run:
```bash
GEMINI_API_KEY=...                    # Required for bootRun
SPRING_AI_OPENAI_API_KEY=...          # Optional — OpenAI backend
SPRING_AI_ANTHROPIC_API_KEY=...       # Optional — Anthropic backend
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor  # Optional — prod DB
SPRING_DATASOURCE_USERNAME=arc
SPRING_DATASOURCE_PASSWORD=arc
SPRING_FLYWAY_ENABLED=true            # Optional — DB migrations
```

NEVER set provider API keys with empty string defaults in `application.yml`. Env vars only.

## Validate Commands

```bash
./gradlew compileKotlin compileTestKotlin   # Must produce 0 warnings
./gradlew test                              # Must pass before any PR
./gradlew test --tests "*.YourTestClass"    # Run single test during iteration
./gradlew test -Pdb=true                    # Include PostgreSQL/PGVector/Flyway tests
./gradlew test -PincludeIntegration         # Include @Tag("integration") tests
```

CI merge gate: `build`, `integration`, `docker` checks must all be green.

## Module Structure

| Module | Purpose |
|--------|---------|
| `arc-core` | Core framework: agent executor, guard, hooks, tools, RAG, scheduler, personas |
| `arc-web` | REST controllers, WebFlux, auth, multimodal upload |
| `arc-slack` | Slack integration (Events API + Socket Mode) |

All features are opt-in via `@ConditionalOnMissingBean` — override any bean with your own implementation.

## Architecture at a Glance

Request flow: **Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response**

- `SpringAiAgentExecutor.kt` — Core loop (~1,060 lines). Touch with caution
- `ArcReactorAutoConfiguration.kt` — All beans. Override via `@ConditionalOnMissingBean`
- `AgentPolicyAndFeatureProperties.kt` — All `arc.reactor.*` config properties and feature toggle defaults

**Guard = fail-close** (blocked). **Hook = fail-open** (log & continue). Security logic in Guard only.

## Critical Gotchas (bugs waiting to happen)

1. **CancellationException**: in every `suspend fun`, catch and rethrow `CancellationException` BEFORE any generic `catch (e: Exception)`. Failure breaks structured concurrency silently
2. **ReAct infinite loop**: when `maxToolCalls` is reached, set `activeTools = emptyList()`. Logging only causes an infinite loop
3. **.forEach in coroutines**: use `for (item in list)` not `list.forEach {}`. The lambda is not suspend-capable
4. **Message pair integrity**: `AssistantMessage(toolCalls)` and `ToolResponseMessage` must always be added/removed as a pair
5. **Context trimming**: Phase 2 guard condition is `>` not `>=`. Off-by-one drops the last UserMessage
6. **AssistantMessage constructor**: it is protected — use `AssistantMessage.builder().content().toolCalls().build()`
7. **API key env vars**: NEVER set provider keys with empty string defaults in `application.yml`
8. **MCP servers**: registered via REST API only (`POST /api/mcp/servers`). Do NOT hardcode in `application.yml`
9. **Guard null userId**: always fall back to `"anonymous"`. Skipping the guard is a security vulnerability
10. **Spring AI mock chain**: explicitly mock `.options(any<ChatOptions>())` in streaming tests
11. **toolsUsed list**: only append a tool name after confirming its adapter exists

## Key Defaults

| Property | Default | Property | Default |
|----------|---------|----------|---------|
| `max-tool-calls` | 10 | `concurrency.request-timeout-ms` | 30000 |
| `max-tools-per-request` | 30 | `concurrency.tool-call-timeout-ms` | 15000 |
| `llm.temperature` | 0.1 | `guard.rate-limit-per-minute` | 20 |
| `llm.max-context-window-tokens` | 128000 | `guard.rate-limit-per-hour` | 200 |
| `boundaries.input-max-chars` | 10000 | | |

## Code Rules

- All interfaces are coroutine-based (`suspend fun`). No blocking calls except `ArcToolCallbackAdapter` (Spring AI constraint)
- Method ≤20 lines, line ≤120 chars. English-only comments and KDoc
- Logging: `private val logger = KotlinLogging.logger {}` at file top-level, before the class
- All controllers need `@Tag`. All endpoints need `@Operation(summary = "...")`
- Admin auth: use `AdminAuthSupport.isAdmin(exchange)` and `forbiddenResponse()` — do NOT duplicate
- All 403 responses MUST include `ErrorResponse` body — never empty `build()`

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
- For timing-sensitive tests: use `CountDownLatch` + `Thread.sleep()`, NOT `delay()` (virtual clock only)

## PR Rules

- Include cost impact notes for any PR that adds LLM calls
- Patch/minor dep upgrades: merge after green CI. Major: migration notes + rollback plan required
- Spring Boot major upgrades are blocked without explicit maintainer approval
