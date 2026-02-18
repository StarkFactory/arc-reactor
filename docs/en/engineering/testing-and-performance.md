# Testing and Performance Guide

This guide documents current test scope defaults and practical speed optimization tactics.

## Default Test Scope

All modules exclude `@Tag("integration")` tests by default.

- default run: `./gradlew test --continue`
- include integration: `./gradlew test -PincludeIntegration`
- integration API suite (core + web): `./gradlew :arc-core:test :arc-web:test -PincludeIntegration --tests "com.arc.reactor.integration.*"`
- include external integration (npx/docker/network): `./gradlew test -PincludeIntegration -PincludeExternalIntegration`

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

CI now enforces time budgets using `scripts/ci/run-with-duration-guard.sh`:

- unit test suite: 90s
- integration API suite: 150s

If execution exceeds the budget, CI fails fast with a clear duration error.
Integration gate also verifies that external MCP integration tests are excluded.

## CI Structural Guard

CI also enforces file-size guardrails for key orchestration/configuration files:

- `SpringAiAgentExecutor.kt` <= 900 lines
- `ArcReactorCoreBeansConfiguration.kt` <= 350 lines
- `AgentPolicyAndFeatureProperties.kt` <= 500 lines

Guard script: `scripts/ci/check-file-size-guard.sh`

## CI Documentation Guard

CI enforces documentation consistency and navigability:

- `scripts/ci/check-agent-doc-sync.sh` verifies `AGENTS.md` and `CLAUDE.md` are identical.
- `scripts/ci/check-doc-links.py` verifies local markdown links and package README indexes in `docs/en` + `docs/ko`.

## H2/JDBC Validation

For DB-related behavior, keep slice/integration tests reproducible:

- use H2 for fast local regression checks
- reserve containerized integration for explicit `integration` runs

## Related

- [Feature Inventory](../reference/feature-inventory.md)
- [Module Layout](../architecture/module-layout.md)
