# Arc Reactor - Agent Instructions

This repository is a Spring AI-based AI Agent project.  
These instructions are adapted from `CLAUDE.md` for coding agents (including Codex).

## Project Summary

- Kotlin 2.3.x, Spring Boot 3.5.x, Spring AI 1.1.2, JDK 21, Gradle 8.x
- Multi-module:
  - `arc-core/`: agent engine + main app
  - `arc-web/`: REST API gateway
  - `arc-slack/`, `arc-discord/`, `arc-line/`: channel gateways

## Instruction Sync

- Keep `AGENTS.md` and `CLAUDE.md` synchronized.
- When rules change, update both files in the same change set.

## Core Rules

- Security-critical logic belongs in **Guard**, not Hook.
- Guard is **fail-close**; Hook is default **fail-open** unless configured otherwise.
- In all `suspend fun`, always rethrow `CancellationException` before generic `Exception`.
- On ReAct `maxToolCalls`, force `activeTools = emptyList()` (do not only log).
- Keep Assistant tool-call + ToolResponse message pair integrity when trimming history.
- Fallback user identity must be `"anonymous"` when userId is missing.
- Add `toolsUsed` only after tool adapter existence is confirmed.
- Approval endpoints must enforce ownership/admin authorization.
- If approval returns `modifiedArguments`, tool execution must apply them.
- Streaming behavior must follow the same output-policy expectations as non-streaming.
- MCP server update paths must keep runtime manager state in sync before reconnect.

## Coding Conventions

- Coroutine-first style (`suspend fun`).
- Keep methods concise (target <=20 lines where practical).
- Keep line length around 120 chars.
- English comments/KDoc.
- Use top-level logger pattern:
  - `private val logger = KotlinLogging.logger {}`
- Prefer safe null handling (`orEmpty`, Elvis) over non-null assertions.
- Controller standards:
  - `@Tag` on controllers
  - `@Operation(summary = "...")` on endpoints

## Architecture Notes

Request flow:
`Guard -> Hook(BeforeStart) -> ReAct Loop(LLM <-> Tool) -> Hook(AfterComplete) -> Response`

Key files:
- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorAutoConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/agent/config/AgentProperties.kt`

## Feature Toggles (Important)

- Guard: `arc.reactor.guard.enabled` (default ON)
- Security headers: `arc.reactor.security-headers.enabled` (default ON)
- Auth/JWT: `arc.reactor.auth.enabled` (default OFF)
- RAG: `arc.reactor.rag.enabled` (default OFF)
- CORS: `arc.reactor.cors.enabled` (default OFF)
- Circuit breaker: `arc.reactor.circuit-breaker.enabled` (default OFF)

## API/Behavior Rules

- Streaming endpoint emits: `message`, `tool_start`, `tool_end`, `error`, `done`.
- Structured output:
  - Supported: `TEXT`, `JSON`, `YAML`
  - Streaming supports only `TEXT`.
- Error DTO consistency:
  - Use `ErrorResponse(error, details?, timestamp)`.
  - 403 responses should include `ErrorResponse` body.

## Admin/Auth Semantics

- Admin-only writes for Persona/Document/MCP/PromptTemplate APIs.
- Reuse shared helpers in `AdminAuthSupport.kt`; do not duplicate admin checks.
- Current semantics: when auth is disabled and role is missing, requests are treated as admin.

## MCP Rules

- Register MCP servers via REST API (`/api/mcp/servers`), not hardcoded.
- Supported transports: `STDIO`, `SSE`.
- HTTP streamable transport is not supported in current MCP SDK.
- Persistence: in-memory by default, JDBC when DataSource exists.

## Dependency/Provider Rules

- Do not configure provider keys with empty defaults in `application.yml`.
- Use env vars for provider keys:
  - `GEMINI_API_KEY`
  - `SPRING_AI_OPENAI_API_KEY`
  - `SPRING_AI_ANTHROPIC_API_KEY`

## Testing Expectations

- New feature -> tests required.
- Bug fix -> regression test required.
- TDD by default: Red -> Green -> Refactor.
- For bug fixes, create a failing test first, then implement fix.
- If tests are intentionally skipped, state explicit reason in PR/commit.
- Prefer shared test utilities:
  - `AgentTestFixture`
  - `AgentResultAssertions`
- Prefer `runTest` for coroutine tests and `coEvery/coVerify` for suspend mocks.

## Recommended Development Methodology

- Workflow: TDD by default (`Red -> Green -> Refactor`) for behavior changes.
- Test strategy:
  - Unit tests for pure domain logic
  - Slice tests for boundaries (`@WebFluxTest`, `@DataJdbcTest`, etc.)
  - Integration tests for infra paths with Testcontainers + `@ServiceConnection`
- Prefer constructor injection for required dependencies.
- Transaction boundaries:
  - Place `@Transactional` on concrete service entry points
  - Avoid self-invocation of transactional methods
- Prefer typed `@ConfigurationProperties` for structured config.
- Observability-first: ensure logs/metrics/traces for production-critical flows.
- DoD: tests pass, docs/config updated, compatibility checked, failure paths observable.

### External Standards

- https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html
- https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- https://docs.spring.io/spring-boot/reference/actuator/observability.html
- https://kotlinlang.org/docs/coding-conventions.html
- https://docs.junit.org/5.11.4/user-guide/

## Useful Commands

```bash
./gradlew test
./gradlew :arc-core:test
./gradlew :arc-web:test
./gradlew compileKotlin compileTestKotlin
./gradlew :arc-core:bootRun
```

Optional profiles:
- `-Pdb=true` (JDBC/PG/Flyway runtime deps)
- `-Pauth=true` (JWT/Security crypto runtime deps)
- `-PincludeIntegration` (include `@Tag("integration")` tests)

## References

- `docs/en/architecture.md`
- `docs/en/tools.md`
- `docs/en/supervisor-pattern.md`
- `docs/en/implementation-guide.md`
