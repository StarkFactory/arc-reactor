# Kotlin & Spring Conventions

## Kotlin

- `suspend fun` — all major interfaces (agent, tool, guard, hook) are coroutine-based
- `ArcToolCallbackAdapter` uses `runBlocking(Dispatchers.IO)` (Spring AI interface constraint)
- Never compile Regex in hot paths. Extract to `companion object` or top-level `val`
- Prefer `content.orEmpty()` over `content!!` (avoids unnecessary non-null assertion warnings)

## Spring Boot

- `@ConditionalOnMissingBean` — applied to all auto-configured beans. Users can override with their own beans
- `@ConditionalOnClass` + `@ConditionalOnBean` — optional dependencies (PostgreSQL, VectorStore, etc.)
- `compileOnly` dependency = optional. Users switch to `implementation` when needed
- Example packages: `@Component` is commented out. Prevents auto-registration in production

## New Feature Checklist

1. Define interface (keep it extensible)
2. Provide default implementation
3. Register bean in `ArcReactorAutoConfiguration` (`@ConditionalOnMissingBean`)
4. Write tests (use `AgentTestFixture`)
5. `./gradlew compileKotlin compileTestKotlin` — verify 0 warnings
6. `./gradlew test` — verify all tests pass
