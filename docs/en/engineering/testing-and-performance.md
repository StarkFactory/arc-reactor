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

## Common Slow-Test Causes

- long network/connection timeout values in negative-path tests
- reconnect loops enabled in tests that validate failure behavior
- thread/latch misconfiguration that forces fixed waits
- external dependency startup/download (e.g., MCP `npx` server boot)

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

## H2/JDBC Validation

For DB-related behavior, keep slice/integration tests reproducible:

- use H2 for fast local regression checks
- reserve containerized integration for explicit `integration` runs

## Related

- [Feature Inventory](../reference/feature-inventory.md)
- [Module Layout](../architecture/module-layout.md)
