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
./gradlew test -Pauth=true                                 # Include JWT/Spring Security Crypto deps
./gradlew test -PincludeIntegration                        # Include @Tag("integration") tests
```

## Architecture

Request flow: **Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response**

| File | Role |
|------|------|
| `agent/impl/SpringAiAgentExecutor.kt` | Core ReAct loop (~1,060 lines). Modify with caution |
| `autoconfigure/ArcReactorAutoConfiguration.kt` | All bean auto-configuration |
| `agent/multi/SupervisorOrchestrator.kt` | Multi-agent orchestration |
| `agent/config/AgentProperties.kt` | All settings (`arc.reactor.*`) |
| `memory/ConversationManager.kt` | Conversation history management (extracted from executor) |
| `test/../AgentTestFixture.kt` | Shared mock setup for agent tests |

### Domain Terms

| Term | Meaning |
|------|---------|
| **ReAct** | Reasoning + Acting loop (LLM thinks → uses tools → repeats) |
| **Guard** | Pre-validation pipeline (5 built-in stages, fail-close) |
| **Hook** | Lifecycle extensions (4 types: BeforeStart, AfterComplete, etc., default fail-open) |
| **MCP** | Model Context Protocol — external tool servers |
| **WorkerAgentTool** | Agent wrapped as ToolCallback (Supervisor pattern core) |

### Guard vs Hook Error Policy

- **Guard**: Always **fail-close** — rejection = request blocked. Security-critical logic MUST go here
- **Hook**: Default **fail-open** (log & continue). Set `failOnError=true` for fail-close

### Feature Toggles

| Feature | Default | Property |
|---------|---------|----------|
| Guard | ON | `arc.reactor.guard.enabled` |
| Unicode Normalization | ON | `arc.reactor.guard.unicode-normalization-enabled` |
| Classification | OFF | `arc.reactor.guard.classification-enabled` |
| LLM Classification | OFF | `arc.reactor.guard.classification-llm-enabled` |
| Canary Token | OFF | `arc.reactor.guard.canary-token-enabled` |
| Tool Output Sanitizer | OFF | `arc.reactor.guard.tool-output-sanitization-enabled` |
| Guard Audit | ON | `arc.reactor.guard.audit-enabled` |
| Security Headers | ON | `arc.reactor.security-headers.enabled` |
| Auth (JWT) | OFF | `arc.reactor.auth.enabled` |
| RAG | OFF | `arc.reactor.rag.enabled` |
| CORS | OFF | `arc.reactor.cors.enabled` |
| Circuit Breaker | OFF | `arc.reactor.circuit-breaker.enabled` |
| Feedback | OFF | `arc.reactor.feedback.enabled` |
| Memory Summary | OFF | `arc.reactor.memory.summary.enabled` |
| Flyway | OFF | `SPRING_FLYWAY_ENABLED` env var |

### REST API Endpoints

| Controller | Base Path | Condition |
|------------|-----------|-----------|
| ChatController | `/api/chat` | Always |
| SessionController | `/api/sessions`, `/api/models` | Always |
| PersonaController | `/api/personas` | Always |
| PromptTemplateController | `/api/prompt-templates` | Always (write = Admin) |
| McpServerController | `/api/mcp/servers` | Always (write = Admin) |
| AuthController | `/api/auth` | `auth.enabled=true` |
| DocumentController | `/api/documents` | `rag.enabled=true` |

### Key Defaults

| Property | Default | Property | Default |
|----------|---------|----------|---------|
| `max-tool-calls` | 10 | `concurrency.request-timeout-ms` | 30000 |
| `max-tools-per-request` | 20 | `concurrency.tool-call-timeout-ms` | 15000 |
| `llm.temperature` | 0.3 | `guard.rate-limit-per-minute` | 10 |
| `llm.max-context-window-tokens` | 128000 | `guard.max-input-length` | 10000 |
| `boundaries.input-max-chars` | 5000 | | |

Full config: `agent/config/AgentPolicyAndFeatureProperties.kt`

### Structured Output & SSE

- `ResponseFormat`: `TEXT` (default), `JSON`, `YAML`. Streaming rejects non-TEXT formats
- Invalid JSON/YAML → one LLM repair call → still invalid → `INVALID_RESPONSE` error
- `POST /api/chat/stream` emits: `message`, `tool_start`, `tool_end`, `error`, `done`

### System Prompt Resolution (priority order)

1. `personaId` → PersonaStore lookup
2. `promptTemplateId` → active PromptVersion content
3. `request.systemPrompt` → direct override
4. Default Persona (`isDefault=true`) from PersonaStore
5. Hardcoded fallback: "You are a helpful AI assistant..."

### Error Responses

- Standard DTO: `ErrorResponse(error, details?, timestamp)` from `GlobalExceptionHandler.kt`
- `GlobalExceptionHandler`: validation → 400 (field details), malformed input → 400, not found → 404, generic → 500 (masked, no stack trace)
- All 403 responses MUST include `ErrorResponse` body — never empty `build()`
- Never use raw `mapOf(...)` for error responses — always use `ErrorResponse`

### Admin Access Control

- MUST use `AdminAuthSupport.kt` — `isAdmin(exchange)` + `forbiddenResponse()`. Do NOT duplicate these
- `isAdmin()` = `role == null || role == ADMIN` — auth disabled means all requests are admin
- Exception: `SessionController` uses its own `sessionForbidden()` (distinct error message "Access denied" vs generic 403)

### Error Codes

`RATE_LIMITED` | `TIMEOUT` | `CONTEXT_TOO_LONG` | `TOOL_ERROR` | `GUARD_REJECTED` | `HOOK_REJECTED` | `INVALID_RESPONSE` | `OUTPUT_GUARD_REJECTED` | `OUTPUT_TOO_SHORT` | `CIRCUIT_BREAKER_OPEN` | `UNKNOWN`

## Code Conventions

- `suspend fun` everywhere — executor, tool, guard, hook are all coroutine-based
- Method ≤20 lines, line ≤120 chars. All comments and KDoc in English
- Logging: `private val logger = KotlinLogging.logger {}` at file top-level (before class)
- Null: `content.orEmpty()` not `content!!`, elvis `?: "anonymous"` for fallbacks
- `compileOnly` = optional dep. Example packages: `@Component` always commented out
- Interfaces at package root, implementations in `impl/`, data classes in `model/`
- All controllers: `@Tag` (Swagger). All endpoints: `@Operation(summary = "...")`

## Testing

```
AgentTestFixture helpers: mockCallResponse(), mockToolCallResponse(), mockFinalResponse(), TrackingTool
AgentResultAssertions:    assertSuccess(), assertFailure(), assertErrorCode(), assertErrorContains()
```

- ALL assertions MUST have failure messages — no bare `assertTrue(x)`
- `assertInstanceOf` over `assertTrue(x is Type)` — returns cast object
- `coEvery`/`coVerify` for suspend mocks. `runTest` over `runBlocking`
- Mock `requestSpec.options(any<ChatOptions>())` explicitly for streaming tests
- `@Nested` inner classes for grouping. `AtomicInteger` for concurrency counting
- Test cadence: focused targets during iteration → full `./gradlew test` before PR
- CI merge gate: `build`, `integration`, `docker` checks must all be green

## New Feature Checklist

1. Define interface (keep it extensible)
2. Provide default implementation
3. Register in `ArcReactorAutoConfiguration` with `@ConditionalOnMissingBean`
4. Write tests with `AgentTestFixture`
5. `./gradlew compileKotlin compileTestKotlin` — verify 0 warnings
6. `./gradlew test` — verify all tests pass

### Extension point rules

- **ToolCallback**: Return errors as strings (`"Error: ..."`) — do NOT throw exceptions
- **GuardStage**: built-in stages use orders 1–5. Use 10+ for custom stages
- **Hook**: MUST wrap logic in try-catch. Always rethrow `CancellationException`
- **Bean**: `@ConditionalOnMissingBean` on all beans. `ObjectProvider<T>` for optional deps. JDBC stores use `@Primary`

## MCP Registration

MCP servers are registered ONLY via REST API — never hardcode in `application.yml`:

```
POST /api/mcp/servers
SSE:   { "name": "my-server", "transportType": "SSE", "config": { "url": "http://localhost:8081/sse" } }
STDIO: { "name": "fs-server", "transportType": "STDIO", "config": { "command": "npx", "args": [...] } }
```

- HTTP transport: NOT supported in MCP SDK 0.17.2
- `autoConnect` defaults to `true`. SSE timeout: 30s. Output truncated at 50,000 chars
- Persistence: InMemory (default) or JDBC (auto with DataSource)

## Critical Gotchas

These cause subtle bugs — read before touching the ReAct loop or coroutine boundaries:

- **CancellationException**: catch & rethrow BEFORE generic `Exception` in ALL `suspend fun`. Breaking this corrupts structured concurrency
- **ReAct maxToolCalls**: set `activeTools = emptyList()` when limit reached. Logging only → infinite loop
- **.forEach in coroutines**: use `for` loop. `.forEach {}` creates a non-suspend lambda
- **Context trimming**: protect last UserMessage. Phase 2 guard uses `>` not `>=`
- **Message pair integrity**: `AssistantMessage(toolCalls)` + `ToolResponseMessage` always removed together
- **Guard null userId**: use "anonymous" fallback. Skipping guard = security vulnerability
- **toolsUsed**: only add after confirming adapter exists (prevents LLM-hallucinated tool names)
- **AssistantMessage**: constructor is protected → `AssistantMessage.builder().content().toolCalls().build()`
- **Spring AI providers**: NEVER declare provider keys with empty defaults in `application.yml`. Env vars only: `GEMINI_API_KEY`, `SPRING_AI_OPENAI_API_KEY`, `SPRING_AI_ANTHROPIC_API_KEY`
- **Spring AI mock chain**: explicitly mock `.options(any<ChatOptions>())` returning requestSpec

## PR and Dependency Policy

- Required CI merge gates: `build`, `integration`, `docker`
- Any feature adding LLM calls requires cost impact notes in the PR description
- Track per-request metrics: `llm_calls_total`, `prompt_tokens`, `completion_tokens`, `tool_calls_total`, `estimated_cost_usd`
- Patch/minor dep upgrades: merge after green CI. Major: require migration notes + rollback plan
- Spring Boot major upgrades blocked without explicit maintainer approval

## Docs

- `docs/en/architecture/` — detailed architecture
- `docs/en/reference/tools.md` — tools reference
- `docs/en/engineering/testing-and-performance.md` — test patterns and examples
