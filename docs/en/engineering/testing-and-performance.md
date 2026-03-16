# Testing and Performance Guide

This guide documents current test scope defaults and practical speed optimization tactics.

## Test Tags

| Tag | Purpose | Gradle flag | Default |
|-----|---------|-------------|---------|
| `@Tag("integration")` | Tests requiring external dependencies (DB, Spring context) | `-PincludeIntegration` | excluded |
| `@Tag("matrix")` | Combinatorial/fuzz regression suites | `-PincludeMatrix` | excluded |
| `@Tag("external")` | Network/NPX/Docker dependencies | `-PincludeExternalIntegration` (requires `-PincludeIntegration`) | excluded |
| `@Tag("safety")` | Security validation gate (CI only) | `-PincludeSafety` | excluded; include-only (runs ONLY safety-tagged tests) |
| `@Tag("regression")` | Targeted regression markers | none | always included |

Tag exclusion is configured in the root `build.gradle.kts` `tasks.withType<Test>` block (applied to all subprojects). The `safety` tag uses `includeTags` instead of `excludeTags`, meaning `-PincludeSafety` runs ONLY safety-tagged tests.

## Default Test Scope

All modules exclude `@Tag("integration")`, `@Tag("external")`, and `@Tag("matrix")` tests by default.

- default run: `./gradlew test --continue`
- include integration: `./gradlew test -PincludeIntegration`
- include matrix/fuzz suites: `./gradlew test -PincludeMatrix`
- integration API suite (core + web): `./gradlew :arc-core:test :arc-web:test -PincludeIntegration --tests "com.arc.reactor.integration.*"`
- include external integration (npx/docker/network): `./gradlew test -PincludeIntegration -PincludeExternalIntegration`
- run safety gate only: `./gradlew :arc-core:test :arc-web:test -PincludeSafety`

This keeps local feedback loops fast while allowing explicit integration coverage when needed.

## Fast Local Validation Strategy

Run in this order for large changes:

1. targeted module tests
2. touched-package test suites
3. full `test --continue`

Examples:

- `./gradlew :arc-core:test --tests "com.arc.reactor.mcp.*"`
- `./gradlew :arc-web:test --tests "com.arc.reactor.controller.*"`
- `./gradlew test --continue`

Developer helpers:

- fast local default suite: `scripts/dev/test-fast.sh`
- include integration tests: `INCLUDE_INTEGRATION=1 scripts/dev/test-fast.sh`
- include matrix/fuzz tests: `INCLUDE_MATRIX=1 scripts/dev/test-fast.sh`
- include external integration tests: `INCLUDE_EXTERNAL=1 scripts/dev/test-fast.sh`
- inspect slowest suites from existing XML reports: `scripts/dev/slow-tests.sh 30`
- run docs/sync quality checks locally: `scripts/dev/check-docs.sh`

## Common Slow-Test Causes

- long network/connection timeout values in negative-path tests
- reconnect loops enabled in tests that validate failure behavior
- thread/latch misconfiguration that forces fixed waits
- external dependency startup/download (e.g., MCP `npx` server boot)
- repeated use of `--no-daemon`/`--rerun-tasks`/`--no-build-cache` for local loops
- running multiple Gradle test commands concurrently in the same workspace

## Matrix/Fuzz Policy

- Put high-volume combinations/randomized regression tests under `@Tag("matrix")`.
- Keep `matrix` suites out of default PR runs for speed.
- Run them on demand with `-PincludeMatrix` and in nightly CI.

## Recommendations

- use short test timeouts for failure-path tests
- disable reconnect by default in unit tests unless reconnect is under test
- keep integration tests tagged and opt-in
- prefer deterministic invalid endpoints over externally flaky targets

## Gradle Runtime Defaults

`gradle.properties` now includes practical speed defaults for local contributors:

- `org.gradle.daemon=true`
- `org.gradle.parallel=true`
- `org.gradle.caching=true`
- `kotlin.incremental=true`

## CI Duration Guard

CI enforces time budgets using `scripts/ci/run-with-duration-guard.sh`:

| Suite | Budget | CI env var |
|-------|--------|------------|
| safety gate (`-PincludeSafety`) | 120s | `SAFETY_TEST_MAX_SECONDS` |
| integration API suite (`-PincludeIntegration`) | 150s | `INTEGRATION_TEST_MAX_SECONDS` |
| API regression flow (single test) | 120s | hardcoded in `ci.yml` |
| pre-open gate (full preflight) | 1200s | `PRE_OPEN_MAX_SECONDS` |
| nightly matrix (`-PincludeMatrix`) | 420s | `MATRIX_TEST_MAX_SECONDS` |

If execution exceeds the budget, CI fails fast with a clear duration error.
The integration gate also verifies that external MCP integration tests are excluded.

Nightly matrix workflow:

- `.github/workflows/nightly-matrix.yml` runs `:arc-core:test -PincludeMatrix` on a schedule.
- workflow step summary includes parsed JUnit totals + failed test cases (`scripts/ci/summarize-junit-failures.sh`)
- optional Slack failure notification is sent when `NIGHTLY_MATRIX_SLACK_WEBHOOK_URL` secret is configured

## CI Structural Guard

CI also enforces file-size guardrails for key orchestration/configuration files:

- `SpringAiAgentExecutor.kt` <= 900 lines
- `ArcReactorCoreBeansConfiguration.kt` <= 350 lines
- `AgentPolicyAndFeatureProperties.kt` <= 500 lines

Guard script: `scripts/ci/check-file-size-guard.sh`

## CI Documentation Guard

CI enforces documentation consistency and navigability:

- `scripts/ci/check-agent-doc-sync.sh` verifies `AGENTS.md` contains all critical sections from `CLAUDE.md` (subset check, not byte-identical).
- `scripts/ci/check-doc-links.py` verifies local markdown links and package README indexes in `docs/en` + `docs/ko`.
- `scripts/ci/check-default-config-alignment.py` verifies default config values in docs match the source code.

## CI Flyway Migration Guard

CI enforces immutability of existing Flyway migrations:

- `scripts/ci/check-flyway-migration-immutability.sh` fails when any already-versioned `V*.sql` file is modified, deleted, or renamed.
- New versioned migrations (added files) pass the guard.

## Test Fixtures and Assertions

### AgentTestFixture

`arc-core/src/test/kotlin/com/arc/reactor/agent/AgentTestFixture.kt`

Shared mock setup for agent tests. Eliminates duplicated `ChatClient` / `RequestSpec` / `CallResponseSpec` mock wiring.

Instance methods:

| Method | Purpose |
|--------|---------|
| `mockCallResponse(content)` | Set up a simple successful call response |
| `mockToolCallResponse(toolCalls)` | Create a `CallResponseSpec` containing tool calls (triggers ReAct loop) |
| `mockFinalResponse(content)` | Create a `CallResponseSpec` for a final (no tool call) response |

Companion object helpers:

| Method | Purpose |
|--------|---------|
| `simpleChatResponse(content)` | Build a `ChatResponse` with text content (no tool calls) |
| `defaultProperties()` | Build `AgentProperties` with request timeout disabled for `runTest` |
| `toolCallback(name, description, result)` | Create a simple `ToolCallback` returning a fixed result |
| `delayingToolCallback(name, delayMs, result)` | Create a `ToolCallback` with coroutine delay (NOT `Thread.sleep`) |
| `textChunk(text)` | Create a `ChatResponse` chunk with text content (streaming tests) |
| `toolCallChunk(toolCalls, text)` | Create a `ChatResponse` chunk with tool calls (streaming tests) |

### TrackingTool

`TrackingTool` is a `ToolCallback` implementation that records call count and captured arguments:

```kotlin
val tracker = TrackingTool("search", result = "found it")
// ... run agent ...
assertEquals(2, tracker.callCount, "search should be called twice")
assertEquals("query-value", tracker.capturedArgs[0]["query"], "first call should pass query")
```

### AgentResultAssertions

`arc-core/src/test/kotlin/com/arc/reactor/agent/AgentResultAssertions.kt`

Extension functions on `AgentResult` that surface actual errors on failure:

| Method | Purpose |
|--------|---------|
| `assertSuccess(message)` | Assert `success == true`, show `errorMessage` on failure |
| `assertFailure(message)` | Assert `success == false`, show `content` on failure |
| `assertErrorContains(expected)` | Assert failure with `errorMessage` containing `expected` (case-insensitive) |
| `assertErrorCode(expected)` | Assert failure with specific `AgentErrorCode` |

## Gradle Test JVM Configuration

The root `build.gradle.kts` configures test JVM args for all subprojects:

```kotlin
tasks.withType<Test> {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    jvmArgs(
        "-XX:+UseParallelGC",
        "-XX:+TieredCompilation",
        "-XX:TieredStopAtLevel=1"
    )
}
```

## H2/JDBC Validation

For DB-related behavior, keep slice/integration tests reproducible:

- use H2 for fast local regression checks
- reserve containerized integration for explicit `integration` runs

## Related

- [Feature Inventory](../reference/feature-inventory.md)
- [Module Layout](../architecture/module-layout.md)
