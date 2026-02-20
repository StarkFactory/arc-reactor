# Shared Skill: TDD Workflow

This workflow is tool-agnostic and intended for both Claude Code and Codex.

## Goal

Ship behavior changes with low regression risk by enforcing `Red -> Green -> Refactor` and scenario-based verification.

## When To Use

- New feature implementation
- Bug fix
- Refactoring that can affect runtime behavior
- Gateway flows (Slack, Discord, LINE, Web)
- Guard / Hook / RAG / MCP orchestration changes

## Workflow

1. Define scope and failure mode.
2. Add or update a test that reproduces the expected behavior first (Red).
3. Run the smallest relevant test slice and confirm it fails for the right reason.
4. Implement the minimal production change (Green).
5. Re-run the same test slice until stable.
6. Refactor for readability and consistency without changing behavior.
7. Run broader regression suites and module-wide tests.
8. Update docs/rules only if behavior or policy changed.

## Test-First Checklist

- Test names describe user-observable behavior.
- Assertions include failure messages.
- Type checks use `assertInstanceOf` instead of `assertTrue(x is Type)`.
- Coroutine tests use `runTest`.
- Suspend mocks use `coEvery` / `coVerify`.
- Security-sensitive paths include rejection/failure tests.

## Scenario Matrix Template

Use this template for gateway or orchestration changes:

| Scenario | Input | Expected Result | Test |
|---|---|---|---|
| Happy path | valid request | success response | unit/slice test |
| Validation failure | malformed input | 400 + structured error body | unit/slice test |
| Auth/Guard reject | unauthorized or blocked input | 401/403 or guard rejection | unit/integration test |
| Downstream failure | tool/network/store failure | fail-open or fail-close as designed | unit/integration test |
| Recovery path | retry/fallback/reconnect | graceful recovery | integration test |

## Command Sequence

```bash
# 1) Smallest impacted test slice first
./gradlew :arc-core:test --tests "*TargetTest"

# 2) Module regression
./gradlew :arc-core:test
./gradlew :arc-web:test
./gradlew :arc-slack:test

# 3) Full regression
./gradlew test

# 4) Documentation/rule consistency checks (if instruction files or docs changed)
bash scripts/ci/check-agent-doc-sync.sh
bash scripts/dev/check-docs.sh
```

## Definition Of Done

- Failing test was written first for behavior changes.
- Relevant module tests pass.
- Full `./gradlew test` passes.
- No policy regressions in guard/auth/error handling.
- Documentation and instruction files are updated when required.

