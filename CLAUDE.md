# Arc Reactor

Spring AI-based AI Agent framework (Kotlin/Spring Boot). Fork and attach tools to use.

## Tech Stack

- Kotlin 2.3.10, Spring Boot 3.5.9, Spring AI 1.1.2, JDK 21, Gradle 8.12
- Test: JUnit 5 + MockK 1.14.9 + Kotest assertions 5.9.1
- DB: H2 (test), PostgreSQL (prod, optional)

## Commands

```bash
./gradlew test                                             # Full test suite
./gradlew test --tests "com.arc.reactor.agent.*"           # Package filter
./gradlew test --tests "*.SpringAiAgentExecutorTest"       # Single file
./gradlew compileKotlin compileTestKotlin                  # Compile check (0 warnings required)
./gradlew bootRun                                          # Run (GEMINI_API_KEY required)
./gradlew test -Pdb=true                                   # Include PostgreSQL/PGVector/Flyway deps
./gradlew test -PincludeIntegration                        # Include @Tag("integration") tests
```


## Default Config Quick Reference

| Key | Default | Key | Default |
|-----|---------|-----|---------|
| `max-tool-calls` | 10 | `concurrency.request-timeout-ms` | 30000 |
| `max-tools-per-request` | 20 | `concurrency.tool-call-timeout-ms` | 15000 |
| `llm.temperature` | 0.3 | `guard.rate-limit-per-minute` | 10 |
| `llm.max-context-window-tokens` | 128000 | `guard.rate-limit-per-hour` | 100 |
| `boundaries.input-max-chars` | 5000 | | |

## Architecture

Request flow: **Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response**

| File | Role |
|------|------|
| `agent/impl/SpringAiAgentExecutor.kt` | Core ReAct loop (~1,060 lines). Modify with caution |
| `autoconfigure/ArcReactorAutoConfiguration.kt` | All bean auto-configuration |
| `agent/multi/SupervisorOrchestrator.kt` | Multi-agent orchestration |
| `agent/config/AgentProperties.kt` | All settings (`arc.reactor.*`) |
| `agent/config/AgentPolicyAndFeatureProperties.kt` | All feature toggle defaults |
| `memory/ConversationManager.kt` | Conversation history management |
| `test/../AgentTestFixture.kt` | Shared mock setup for agent tests |

**Guard = fail-close** (rejected = request blocked). **Hook = fail-open** (log & continue, set `failOnError=true` for fail-close). Security logic MUST go in Guard only.

## Critical Gotchas

These cause subtle bugs — read before touching the ReAct loop or coroutine boundaries:

- **CancellationException**: catch & rethrow BEFORE generic `Exception` in ALL `suspend fun`. Breaking this corrupts structured concurrency
- **ReAct maxToolCalls**: set `activeTools = emptyList()` when limit reached. Logging only → infinite loop
- **.forEach in coroutines**: use `for` loop. `.forEach {}` creates a non-suspend lambda
- **Message pair integrity**: `AssistantMessage(toolCalls)` + `ToolResponseMessage` always added/removed together
- **Context trimming**: protect last UserMessage. Phase 2 guard condition uses `>` not `>=`
- **Guard null userId**: use "anonymous" fallback. Skipping guard = security vulnerability
- **Output guard errors**: preserve exact codes `OUTPUT_GUARD_REJECTED` and `OUTPUT_TOO_SHORT` in mappings/tests/docs
- **toolsUsed**: only append after confirming adapter exists (prevents LLM-hallucinated tool names)
- **AssistantMessage**: constructor is protected → `AssistantMessage.builder().content().toolCalls().build()`
- **Spring AI providers**: NEVER declare provider keys with empty defaults in `application.yml`. Env vars only: `GEMINI_API_KEY`, `SPRING_AI_OPENAI_API_KEY`, `SPRING_AI_ANTHROPIC_API_KEY`
- **Spring AI mock chain**: explicitly mock `.options(any<ChatOptions>())` returning requestSpec

## Code Conventions

See @.claude/rules/kotlin-spring.md for full rules. Key non-obvious rules:

- `compileOnly` = optional dep. Example packages: `@Component` always commented out
- Interfaces at package root, implementations in `impl/`, data classes in `model/`
- All 403 responses MUST include `ErrorResponse` body — never empty `build()`
- Admin auth: MUST use `AdminAuthSupport.isAdmin(exchange)` + `forbiddenResponse()` — do NOT duplicate

## Testing

```
AgentTestFixture: mockCallResponse(), mockToolCallResponse(), mockFinalResponse(), TrackingTool
AgentResultAssertions: assertSuccess(), assertFailure(), assertErrorCode(), assertErrorContains()
```

- ALL assertions MUST have failure messages — no bare `assertTrue(x)`
- `coEvery`/`coVerify` for suspend mocks. `runTest` over `runBlocking`
- Mock `requestSpec.options(any<ChatOptions>())` explicitly for streaming tests

## New Feature Checklist

1. Define interface (keep it extensible)
2. Provide default implementation
3. Register in `ArcReactorAutoConfiguration` with `@ConditionalOnMissingBean`
4. Write tests with `AgentTestFixture`
5. `./gradlew compileKotlin compileTestKotlin` — verify 0 warnings
6. `./gradlew test` — verify all tests pass

**Extension point rules:**
- **ToolCallback**: Return `"Error: ..."` strings — do NOT throw
- **GuardStage**: built-in orders 1–5. Custom: start at 10+
- **Hook**: MUST wrap in try-catch. Always rethrow `CancellationException`
- **Bean**: `@ConditionalOnMissingBean` on all beans. `ObjectProvider<T>` for optional deps. JDBC stores use `@Primary`

## MCP Registration

Registered ONLY via REST API — never hardcode in `application.yml`:

```
POST /api/mcp/servers
SSE:   { "name": "my-server", "transportType": "SSE", "config": { "url": "http://localhost:8081/sse" } }
STDIO: { "name": "fs-server", "transportType": "STDIO", "config": { "command": "npx", "args": [...] } }
```

HTTP transport NOT supported in MCP SDK 0.17.2. Output truncated at 50,000 chars.

## PR and Dependency Policy

- Required CI merge gates: `build`, `integration`, `docker`
- Any feature adding LLM calls requires cost impact notes in the PR description
- Patch/minor dep upgrades: merge after green CI. Major: migration notes + rollback plan required
- Spring Boot major upgrades blocked without explicit maintainer approval

## Docs

- `docs/en/architecture/` — detailed architecture
- `docs/en/reference/tools.md` — tools reference
- `docs/en/engineering/testing-and-performance.md` — test patterns and examples
