# Arc Reactor

Spring AI-based AI Agent framework. Fork and attach tools to use.

## Tech Stack

- Kotlin 2.3.10, Spring Boot 3.5.9, Spring AI 1.1.2, JDK 21, Gradle 8.12
- Test: JUnit 5 + MockK 1.14.9 + Kotest assertions 5.9.1
- DB: H2 (test), PostgreSQL (prod, optional)

## Commands

```bash
./gradlew test                                             # Full test suite
./gradlew test --tests "com.arc.reactor.agent.*"           # Package filter
./gradlew test --tests "*.SpringAiAgentExecutorTest"       # Single file
./gradlew compileKotlin compileTestKotlin                  # Compile check (maintain 0 warnings)
./gradlew bootRun                                          # Run (GEMINI_API_KEY required)
./gradlew test -Pdb=true                                   # Include PostgreSQL/PGVector/Flyway deps
./gradlew test -Pauth=true                                 # Include JWT/Spring Security Crypto deps
./gradlew test -PincludeIntegration                        # Include @Tag("integration") tests
```

## Agent Playbook (Optimized)

### Instruction Hierarchy and File Strategy

- Keep `AGENTS.md` as Codex-first canonical project policy; keep it synchronized with this file
- Use nearest-scope instruction files for submodules/packages when rules differ (more specific path wins)
- Keep agent instruction files concise and non-duplicated; prefer linking to detailed docs over repeating long prose
- For Claude workflows, split deep/specialized guidance into modular rule files and import only when needed
- Prefer explicit checklists and executable commands over vague guidance

### PR and CI Gate (Mandatory)

- Required checks are merge gates: `build`, `integration`, `docker`
- Do not merge if required checks are failing or pending
- Keep PR branch up-to-date with target branch before merge when required checks enforce it
- Dependency-only PRs default to squash merge unless maintainers request otherwise

### Cost and LLM Governance

- Any feature that adds LLM calls (for example, summarization/compression paths) must include cost impact review
- For Slack-origin short operational questions, prefer low-cost model routes and keep expensive add-on paths opt-in
- Capture and monitor per-request metrics: `llm_calls_total`, `prompt_tokens`, `completion_tokens`, `tool_calls_total`, `estimated_cost_usd`
- PR descriptions for new LLM paths should include expected call-frequency delta and token/cost assumptions

### Dependency Upgrade Policy

- Patch/minor upgrades: merge after green CI and no behavior-risk findings
- Major upgrades: require compatibility notes, migration impact, and rollback plan
- Spring Boot major upgrades are blocked by default without explicit maintainer approval

## Architecture

Request flow: Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response

- `SpringAiAgentExecutor` (~1,060 lines) — Core executor. Modify with caution
- `ArcReactorAutoConfiguration` — All bean auto-configuration. Override via @ConditionalOnMissingBean
- `ConversationManager` — Conversation history management, extracted from executor

Details: @docs/en/architecture.md, @docs/en/tools.md, @docs/en/supervisor-pattern.md

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
| Memory Summary | OFF | `arc.reactor.memory.summary.enabled` |
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
| `llm.max-context-window-tokens` | 128000 | `guard.max-input-length` | 10000 |

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
- **PR CI gate is mandatory**: open/merge PRs only when required checks (`build`, `integration`, `docker`) are green
- If CI fails, fix root cause (or update the PR with explicit rationale) before requesting/continuing review
- `AgentTestFixture` for agent tests: `mockCallResponse()`, `mockToolCallResponse()`, `mockFinalResponse()`, `TrackingTool`
- `AgentResultAssertions`: `assertSuccess()`, `assertFailure()`, `assertErrorCode()`, `assertErrorContains()`
- IMPORTANT: ALL assertions MUST have failure messages — no bare `assertTrue(x)`
- `assertInstanceOf` over `assertTrue(x is Type)` — returns cast object
- `@Nested` inner classes for logical grouping. `AtomicInteger` for concurrency counting
- `coEvery`/`coVerify` for suspend mocks, `runTest` preferred over `runBlocking`
- Mock `requestSpec.options(any<ChatOptions>())` explicitly for streaming tests

For test patterns and examples: @docs/en/implementation-guide.md

## Implementation Guide

When adding new ToolCallback, GuardStage, Hook, or Bean — follow templates in @docs/en/implementation-guide.md

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
- **Spring AI providers**: NEVER declare provider keys with empty defaults in `application.yml`. Use env vars only: `GEMINI_API_KEY`, `SPRING_AI_OPENAI_API_KEY`, `SPRING_AI_ANTHROPIC_API_KEY`
- **MCP SDK**: Follow version pinned in `build.gradle.kts`. Do not hardcode transport assumptions; verify support matrix before adding new transport behavior

For code examples of anti-patterns: @docs/en/implementation-guide.md

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
| `test/../AgentTestFixture.kt` | Test shared mock setup |
