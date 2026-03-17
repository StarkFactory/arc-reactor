# Arc Reactor — Ecosystem Reference

Quick-reference for environment variables and Gradle flags that activate optional subsystems.

## Cache

| Variable | Default | Description |
|----------|---------|-------------|
| `ARC_REACTOR_CACHE_ENABLED` | `false` | Response cache (in-memory, Caffeine) |
| `ARC_REACTOR_CACHE_SEMANTIC_ENABLED` | `false` | Semantic response cache (requires Redis + EmbeddingModel) |
| `ARC_REACTOR_CACHE_CACHEABLE_TEMPERATURE` | `0.0` | Max temperature eligible for caching |
| `ARC_REACTOR_TOOL_RESULT_CACHE_ENABLED` | `true` | Per-request tool result dedup cache (in-memory) |
| `ARC_REACTOR_TOOL_RESULT_CACHE_TTL_SECONDS` | `60` | Tool result cache TTL |
| `ARC_REACTOR_TOOL_RESULT_CACHE_MAX_SIZE` | `200` | Tool result cache max entries |

## RAG

| Variable | Default | Description |
|----------|---------|-------------|
| `ARC_REACTOR_RAG_ENABLED` | `false` | Enable RAG retrieval pipeline |
| `SPRING_AI_VECTORSTORE_PGVECTOR_INITIALIZE_SCHEMA` | `false` | Auto-create pgvector extension and table on startup |

## Redis

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATA_REDIS_HOST` | _(none)_ | Redis host (required for semantic cache, Redis token revocation) |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |

## Gradle Flags

| Flag | Effect |
|------|--------|
| `-Predis=true` | Include `spring-boot-starter-data-redis` in runtime classpath |
| `-Pdb=true` | Include PostgreSQL, JDBC, PGVector, and Flyway in runtime classpath |
| `-PincludeIntegration` | Run `@Tag("integration")` tests (requires LLM API keys) |
