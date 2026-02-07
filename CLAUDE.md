# Arc Reactor

Spring AI-based AI Agent framework. Fork and attach tools to use.

## Tech Stack

- Kotlin 2.3.0, Spring Boot 3.5.9, Spring AI 1.1.2, JDK 21, Gradle 8.12
- Test: JUnit 5 + MockK 1.14.5 + Kotest assertions 5.9.1
- DB: H2 (test), PostgreSQL (prod, optional)

## Commands

```bash
./gradlew test                                             # All tests (267)
./gradlew test --tests "com.arc.reactor.agent.*"           # Package filter
./gradlew test --tests "*.SpringAiAgentExecutorTest"       # Single file
./gradlew compileKotlin compileTestKotlin                  # Compile check (maintain 0 warnings)
./gradlew bootRun                                          # Run (GEMINI_API_KEY required)
```

## Architecture

Request flow: Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response

- `SpringAiAgentExecutor` (~1,060 lines) — Core executor. Modify with caution
- `ArcReactorAutoConfiguration` — All bean auto-configuration. Override via @ConditionalOnMissingBean
- `ConversationManager` — Conversation history management, extracted from executor

Details: @docs/ko/architecture.md, @docs/ko/tools.md, @docs/ko/supervisor-pattern.md

## Design Principles

- **No circular dependencies** between packages. Dependency direction: controller → agent → guard/hook/tool/memory
- **Depend on interfaces, not implementations** (Dependency Inversion Principle)
- **Single Responsibility** — one reason to change per class
- **Prefer composition over inheritance** — use delegation and interfaces
- **Keep it simple** — no premature abstractions, no over-engineering

## Code Style

- Follow Kotlin conventions. No special formatter (IntelliJ defaults)
- **Method length: ≤20 lines**. Extract method when exceeded
- **Line width: ≤120 chars**. Wrap when exceeded
- **All comments and KDoc in English** (open-source standard, GNU/Apache convention)
- `suspend fun` everywhere — executor, tool, guard, hook are all coroutine-based
- `compileOnly` = optional dependency (user switches to `implementation` when needed)
- Example package classes have `@Component` commented out (prevent auto-registration in production)

## Testing Rules

- **All new features must have tests**. Verify behavior works before merging
- **Every bug fix must include a regression test** that reproduces the original issue
- **Run `./gradlew test` after every change** to ensure nothing breaks
- JUnit 5 assertions (`org.junit.jupiter.api.Assertions.*`). Kotest matchers only (not runner)
- `AgentTestFixture` — shared mock setup for all agent tests
- `AgentResultAssertions` — `assertSuccess()`, `assertFailure()`, `assertErrorCode()` extensions
- All assertions must have failure messages (no bare `assertTrue(x)`)
- `@Nested` inner classes for logical grouping
- Timing tests: `AtomicInteger` concurrency counting (never `System.currentTimeMillis`)
- `assertInstanceOf` over `assertTrue(x is Type)` — returns cast object

## Critical Gotchas

- **CancellationException**: Always catch & rethrow before generic `Exception` in all `suspend fun`. Prevents breaking structured concurrency with withTimeout
- **ReAct loop termination**: On maxToolCalls reached, set `activeTools = emptyList()` — logging only causes infinite loop
- **Context trimming**: Always protect the last UserMessage (current prompt). Phase 2 guard uses `>` (not `>=`)
- **Message pair integrity**: AssistantMessage(toolCalls) + ToolResponseMessage must always be removed together
- **Guard null userId**: Use "anonymous" fallback. Skipping guard entirely is a security vulnerability
- **toolsUsed tracking**: Only add tool name after confirming adapter exists (prevents LLM-hallucinated tool names)
- **Hook/Memory exceptions**: Always wrap hook/memory calls in catch/finally blocks with try-catch
- **Regex**: Never compile in hot paths. Extract to top-level `val`

## Domain Terms

- **ReAct**: Reasoning + Acting. Loop where LLM thinks and uses tools
- **Guard**: Request pre-validation pipeline (5 stages)
- **Hook**: Agent lifecycle extension points (4 hooks)
- **MCP**: Model Context Protocol. Standard for external servers providing tools
- **WorkerAgentTool**: Adapter wrapping an agent as a ToolCallback (core of Supervisor pattern)

## Key Files

| File | Role |
|------|------|
| `agent/impl/SpringAiAgentExecutor.kt` | ReAct loop, retry, context management (core) |
| `agent/multi/SupervisorOrchestrator.kt` | Supervisor multi-agent orchestration |
| `agent/multi/WorkerAgentTool.kt` | Agent-to-tool conversion adapter |
| `autoconfigure/ArcReactorAutoConfiguration.kt` | Spring Boot auto-configuration |
| `controller/ChatController.kt` | REST API endpoints |
| `agent/config/AgentProperties.kt` | All settings (arc.reactor.*) |
| `test/../AgentTestFixture.kt` | Test shared mock setup |
