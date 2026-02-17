# Module Layout Guide

This document explains the current Gradle module boundaries and recommended build/run entrypoints.

## Gradle Modules

From `settings.gradle.kts`:

- `arc-core`: core agent engine, policies, memory/RAG, MCP manager, shared domain logic
- `arc-web`: REST API controllers and web-layer integration
- `arc-slack`: Slack channel gateway
- `arc-discord`: Discord channel gateway
- `arc-line`: LINE channel gateway
- `arc-error-report`: error reporting extension module
- `arc-app`: executable assembly module (runtime composition)

## Why `arc-app` Exists

`arc-core` is now library-style. The executable runtime composition moved to `arc-app`.

- `arc-core`:
  - `bootJar` disabled
  - plain `jar` kept for dependency usage by other modules
- `arc-app`:
  - depends on `arc-core`
  - adds channel/web modules as runtime dependencies
  - declares `mainClass` for executable startup

## Recommended Commands

- Local run: `./gradlew :arc-app:bootRun`
- Executable jar: `./gradlew :arc-app:bootJar`
- Full tests (default): `./gradlew test --continue`
- Include integration tests: `./gradlew test -PincludeIntegration`

## Dependency Direction (High-level)

- channel/web modules depend on `arc-core`
- `arc-app` assembles runtime modules
- `arc-core` should avoid runtime coupling back to channel/web modules

## Related

- [Architecture Overview](architecture.md)
- [Deployment Guide](../getting-started/deployment.md)
- [Testing & Performance Guide](../engineering/testing-and-performance.md)
