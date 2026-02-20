# Arc Reactor

Spring AI-based AI Agent framework. Fork and attach tools to use.

## Language Policy

This is an **open-source project** and is **English-first**:
- Code: variable names, function names, class names
- Comments and KDoc
- Commit messages
- Pull request titles and descriptions
- Code review comments
- Issue titles and descriptions
- English documentation (`docs/en`, `README.md`, `CLAUDE.md`, `AGENTS.md`, etc.)

Allowed exceptions for intentional multilingual support:
- Localized documentation (for example `docs/ko/**`, `README.ko.md`)
- User-facing sample utterances, i18n test data, and multilingual keyword dictionaries

## Tech Stack

- Kotlin 2.3.10, Spring Boot 3.5.9, Spring AI 1.1.2, JDK 21, Gradle 8.12
- Test: JUnit 5 + MockK 1.14.9 + Kotest assertions 5.9.1
- DB: H2 (test), PostgreSQL (prod, optional)

## Instruction Files

- `CLAUDE.md` and `AGENTS.md` must stay aligned.
- When changing project rules, update both files in the same commit.
- For Codex-style agents, `AGENTS.md` is the primary machine-readable instruction file.

## Project Structure

Multi-module Gradle project:

| Module | Role | Boot |
|--------|------|------|
| `arc-app/` | Executable assembly module (wires core + gateways into bootJar) | bootJar |
| `arc-core/` | Agent engine/library (guard, hook, MCP, RAG) | library |
| `arc-web/` | REST API gateway (controllers, Swagger, security headers) | library |
| `arc-slack/` | Slack gateway (webhook, signature verification) | library |
| `arc-discord/` | Discord gateway (Discord4J WebSocket) | library |
| `arc-line/` | LINE gateway (webhook, HMAC-SHA256 signature) | library |

## Commands

```bash
./gradlew test                                                       # All tests
./gradlew :arc-core:test                                             # Engine tests
./gradlew :arc-web:test                                              # Controller tests
./gradlew :arc-slack:test                                            # Slack tests
./gradlew :arc-discord:test                                          # Discord tests
./gradlew :arc-line:test                                             # LINE tests
./gradlew :arc-core:test --tests "com.arc.reactor.agent.*"           # Package filter
./gradlew :arc-core:test --tests "*.SpringAiAgentExecutorTest"       # Single file
./gradlew compileKotlin compileTestKotlin                            # Compile check (maintain 0 warnings)
./gradlew :arc-app:bootRun                                           # Run (GEMINI_API_KEY required)
./gradlew :arc-core:test -Pdb=true                                   # Include PostgreSQL/PGVector/Flyway deps
./gradlew :arc-core:test -Pauth=true                                 # Include JWT/Spring Security Crypto deps
./gradlew :arc-core:test -PincludeIntegration                        # Include @Tag("integration") tests
BASE_URL=http://localhost:18084 scripts/dev/validate-slack-runtime.sh # Live Slack runtime validation (requires Slack env vars)
```

## Architecture

Request flow: Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response

- `SpringAiAgentExecutor` (~620 lines) — Core executor. Modify with caution
- `ArcReactorAutoConfiguration` (entrypoint import) — Auto-configuration entrypoint. Override via @ConditionalOnMissingBean
- `ConversationManager` — Conversation history management, extracted from executor

Details: @docs/en/architecture/architecture.md, @docs/en/reference/tools.md, @docs/en/architecture/supervisor-pattern.md

### Feature Toggles

| Feature | Default | Property |
|---------|---------|----------|
| Guard | ON | `arc.reactor.guard.enabled` |
| Security Headers | ON | `arc.reactor.security-headers.enabled` |
| Auth (JWT) | OFF | `arc.reactor.auth.enabled` |
| RAG | OFF | `arc.reactor.rag.enabled` |
| CORS | OFF | `arc.reactor.cors.enabled` |
| Circuit Breaker | OFF | `arc.reactor.circuit-breaker.enabled` |
| Feedback | OFF | `arc.reactor.feedback.enabled` |
| Flyway | OFF | `SPRING_FLYWAY_ENABLED` env var |

### Guard vs Hook Error Policy

- **Guard**: Always **fail-close** — rejection = request blocked
- **Hook**: Default **fail-open** (log & continue). Set `failOnError=true` for fail-close
- IMPORTANT: Security-critical logic belongs in Guard, not Hook

### Structured Output

- `ResponseFormat`: `TEXT` (default), `JSON`, `YAML`
- JSON/YAML validated via Jackson ObjectMapper / SnakeYAML after LLM response
- Auto-strips markdown code fences (` ```json ` / ` ```yaml `)
- Invalid output → one LLM repair call → still invalid → `INVALID_RESPONSE` error
- Streaming mode rejects all non-TEXT formats (returns error chunk)

### SSE Streaming Events

`POST /api/chat/stream` emits 5 event types: `message` (LLM token), `tool_start`, `tool_end`, `error` (typed error), `done`

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
| FeedbackController | `/api/feedback` | `feedback.enabled=true` |

### System Prompt Resolution (priority order)

1. `personaId` → PersonaStore lookup
2. `promptTemplateId` → active PromptVersion content
3. `request.systemPrompt` → direct override
4. Default Persona (`isDefault=true`) from PersonaStore
5. Hardcoded fallback: "You are a helpful AI assistant..."

### Admin Access Control

- Admin-only: Persona/Document/MCP server/PromptTemplate write ops
- Shared utility: `AdminAuthSupport.kt` — `isAdmin(exchange)` + `forbiddenResponse()`
- **MUST use shared functions** — do NOT duplicate `isAdmin`/`forbidden` in individual controllers
- `isAdmin()` = `role == null || role == ADMIN` — when auth is disabled, **all requests treated as admin**
- Session ownership: `SessionController` uses its own `sessionForbidden()` (different error message)

### Error Responses

- **Standard DTO**: `ErrorResponse(error, details?, timestamp)` from `GlobalExceptionHandler.kt`
- All 403 responses MUST include `ErrorResponse` body (never empty `build()`)
- All error `mapOf(...)` patterns replaced with `ErrorResponse` — maintain consistency
- `GlobalExceptionHandler`: validation→400 (field errors), input→400, generic→500 (masked, no stack trace)

### Error Codes

`RATE_LIMITED` | `TIMEOUT` | `CONTEXT_TOO_LONG` | `TOOL_ERROR` | `GUARD_REJECTED` | `HOOK_REJECTED` | `INVALID_RESPONSE` | `CIRCUIT_BREAKER_OPEN` | `UNKNOWN` — Override via `ErrorMessageResolver` (i18n)

### Key Defaults

| Property | Default | Property | Default |
|----------|---------|----------|---------|
| `max-tool-calls` | 10 | `concurrency.request-timeout-ms` | 30000 |
| `max-tools-per-request` | 20 | `concurrency.tool-call-timeout-ms` | 15000 |
| `llm.temperature` | 0.3 | `guard.rate-limit-per-minute` | 10 |
| `llm.max-context-window-tokens` | 128000 | `boundaries.input-max-chars` | 10000 |

Full config: see `agent/config/AgentProperties.kt`

## Code Conventions

- `suspend fun` everywhere — executor, tool, guard, hook are all coroutine-based
- **Method ≤20 lines, line ≤120 chars**. All comments and KDoc in English
- Logging: top-level `private val logger = KotlinLogging.logger {}` before class
- Null handling: `content.orEmpty()` over `content!!`, elvis `?: "anonymous"` for fallbacks
- `compileOnly` = optional dependency. Example packages: `@Component` always commented out
- Interfaces at package root, implementations in `impl/`, data classes in `model/`
- Swagger: All controllers MUST have `@Tag`. All endpoints MUST have `@Operation(summary = "...")`

## Testing Rules

- **All new features must have tests**. Every bug fix must include a regression test
- **Run `./gradlew test` after every change**
- **TDD by default**: Red -> Green -> Refactor
- For bug fixes, first add a failing test that reproduces the bug, then fix
- If no test is added, include an explicit reason in PR/commit message (docs-only, config-only, or non-behavioral change)
- `AgentTestFixture` for agent tests: `mockCallResponse()`, `mockToolCallResponse()`, `mockFinalResponse()`, `TrackingTool`
- `AgentResultAssertions`: `assertSuccess()`, `assertFailure()`, `assertErrorCode()`, `assertErrorContains()`
- IMPORTANT: ALL assertions MUST have failure messages — no bare `assertTrue(x)`
- `assertInstanceOf` over `assertTrue(x is Type)` — returns cast object
- `@Nested` inner classes for logical grouping. `AtomicInteger` for concurrency counting
- `coEvery`/`coVerify` for suspend mocks, `runTest` preferred over `runBlocking`
- Mock `requestSpec.options(any<ChatOptions>())` explicitly for streaming tests

For test patterns and examples: @docs/en/architecture/implementation-guide.md

## Recommended Development Methodology (Spring + Kotlin)

- **Workflow**: TDD by default (`Red -> Green -> Refactor`) for behavior changes.
- **Test strategy**:
  - Unit tests for pure domain/tool logic
  - Slice tests (`@WebFluxTest`, `@DataJdbcTest`, etc.) for boundaries
  - Integration tests for infrastructure paths with Testcontainers + `@ServiceConnection`
- **Dependency injection**: Prefer constructor injection for required dependencies (immutability + null safety).
- **Transactions**:
  - Put `@Transactional` on concrete service methods/classes
  - Prefer `public` transactional entry points
  - Avoid self-invocation of transactional methods (proxy interception limitation)
- **Configuration**: Prefer typed `@ConfigurationProperties` for structured settings; keep env-based overrides.
- **Observability-first**: Every production feature should be diagnosable via logs/metrics/traces; add custom observations for expensive or failure-prone paths.
- **Definition of Done (DoD)**:
  - tests pass
  - docs/config updated
  - migration/compatibility impact checked
  - key failure mode observable

### Official References

- Spring Boot - DI: https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html
- Spring Boot - Testing/Testcontainers: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- Spring Framework - `@Transactional`: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- Spring Boot - Observability: https://docs.spring.io/spring-boot/reference/actuator/observability.html
- Kotlin Coding Conventions: https://kotlinlang.org/docs/coding-conventions.html
- JUnit 5 User Guide: https://docs.junit.org/5.11.4/user-guide/

## Implementation Guide

When adding new ToolCallback, GuardStage, Hook, or Bean — follow templates in @docs/en/architecture/implementation-guide.md

Key rules:
- **ToolCallback**: Return errors as strings (`"Error: ..."`) — do NOT throw exceptions
- **GuardStage**: order 1-5 are built-in stages. Use 10+ for custom stages
- **Hook**: MUST wrap logic in try-catch. Always rethrow `CancellationException`
- **Bean**: `@ConditionalOnMissingBean` on all beans. `ObjectProvider<T>` for optional deps. JDBC stores use `@Primary`

## MCP Registration

- **IMPORTANT: MCP servers are registered ONLY via REST API**, never hardcoded
- **Do NOT** add MCP server URLs in `application.yml` or create MCP configuration classes

```
POST /api/mcp/servers
SSE:   { "name": "my-server", "transportType": "SSE", "config": { "url": "http://localhost:8081/sse" } }
STDIO: { "name": "fs-server", "transportType": "STDIO", "config": { "command": "npx", "args": [...] } }
```

- `autoConnect` defaults to `true`. SSE timeout: 30s. Output truncated at 50,000 chars
- HTTP transport: **NOT supported** in MCP SDK 0.17.2
- Persistence: InMemory (default, lost on restart) or JDBC (auto with DataSource)

## Critical Gotchas

- **MUST: CancellationException** — Always catch & rethrow before generic `Exception` in ALL `suspend fun`. Prevents breaking structured concurrency
- **MUST: ReAct loop** — On maxToolCalls reached, set `activeTools = emptyList()`. Logging only → infinite loop
- **MUST: .forEach in coroutines** — Use `for` loop instead. `.forEach {}` creates non-suspend lambda
- **Context trimming**: Protect last UserMessage. Phase 2 guard uses `>` (not `>=`)
- **Message pair integrity**: AssistantMessage(toolCalls) + ToolResponseMessage — always remove together
- **Guard null userId**: Use "anonymous" fallback. Skipping guard = security vulnerability
- **toolsUsed**: Only add after confirming adapter exists (prevents LLM-hallucinated tool names)
- **Hook/Memory**: Always wrap in try-catch, log errors, never let exceptions propagate
- **AssistantMessage**: Constructor is protected → use `AssistantMessage.builder().content().toolCalls().build()`
- **Spring AI mock chain**: Explicitly mock `.options(any<ChatOptions>())` returns requestSpec
- **Spring AI providers**: Use environment variables for provider keys (`GEMINI_API_KEY`, `SPRING_AI_OPENAI_API_KEY`, `SPRING_AI_ANTHROPIC_API_KEY`) and never hardcode real keys. Empty local fallbacks are acceptable only for local startup/tests; production must provide env vars.
- **MCP SDK**: Version 0.17.2. SSE only (`HttpClientSseClientTransport`), no streamable HTTP
- **Approval security**: Approve/Reject endpoints MUST enforce ownership or admin authorization
- **HITL argument rewrite**: If approval returns `modifiedArguments`, tool execution MUST use modified arguments
- **Streaming policy parity**: Output guard and response filter policy must match non-streaming behavior, or exception must be explicitly documented
- **MCP update consistency**: MCP server update path must synchronize runtime manager state before reconnect

For code examples of anti-patterns: @docs/en/architecture/implementation-guide.md

## Shared Skills

Tool-agnostic workflow docs usable by both Claude Code and Codex:
- `docs/en/skills/tdd-workflow/SKILL.md` — Test-first delivery and regression safety workflow
- `docs/en/skills/slack-runtime-validation/SKILL.md` — Slack-first runtime and integration validation workflow

## Domain Terms

- **ReAct**: Reasoning + Acting loop (LLM thinks → uses tools → repeats)
- **Guard**: Pre-validation pipeline (5 stages, fail-close)
- **Hook**: Lifecycle extensions (4 types, default fail-open)
- **MCP**: Model Context Protocol (external tool servers)
- **WorkerAgentTool**: Agent wrapped as ToolCallback (Supervisor pattern core)

## Key Files

| File | Role |
|------|------|
| `agent/impl/SpringAiAgentExecutor.kt` | Core ReAct loop, retry, context management |
| `agent/multi/SupervisorOrchestrator.kt` | Multi-agent orchestration |
| `autoconfigure/ArcReactorAutoConfiguration.kt` | All bean auto-configuration |
| `agent/config/AgentProperties.kt` | All settings (`arc.reactor.*`) |
| `arc-core/src/test/kotlin/com/arc/reactor/agent/AgentTestFixture.kt` | Test shared mock setup |
