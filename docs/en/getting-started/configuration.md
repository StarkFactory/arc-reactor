# Configuration Reference & Auto-Configuration

> **Key files:** `AgentProperties.kt`, `ArcReactorAutoConfiguration.kt`
> This document covers all configuration options and the Spring Boot auto-configuration mechanism in Arc Reactor.

## Full Configuration Structure

```yaml
arc:
  reactor:
    max-tools-per-request: 20    # Max tools per request
    max-tool-calls: 10           # Max tool calls in the ReAct loop

    llm:                         # LLM call settings
      temperature: 0.3
      max-output-tokens: 4096
      max-conversation-turns: 10
      max-context-window-tokens: 128000

    retry:                       # LLM retry settings
      max-attempts: 3
      initial-delay-ms: 1000
      multiplier: 2.0
      max-delay-ms: 10000

    guard:                       # Guard pipeline settings
      enabled: true
      rate-limit-per-minute: 10
      rate-limit-per-hour: 100
      max-input-length: 10000
      injection-detection-enabled: true

    rag:                         # RAG pipeline settings
      enabled: false
      similarity-threshold: 0.7
      top-k: 10
      rerank-enabled: true
      max-context-tokens: 4000

    concurrency:                 # Concurrency control
      max-concurrent-requests: 20
      request-timeout-ms: 30000

    cache:                       # Response caching (opt-in)
      enabled: false
      max-size: 1000
      ttl-minutes: 60
      cacheable-temperature: 0.0

    circuit-breaker:             # Circuit breaker (opt-in)
      enabled: false
      failure-threshold: 5
      reset-timeout-ms: 30000
      half-open-max-calls: 1

    fallback:                    # Graceful degradation (opt-in)
      enabled: false
      models: []
```

## Configuration Groups in Detail

### AgentProperties (Root)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `max-tools-per-request` | Int | 20 | Maximum number of tools available per request. `take(n)` is applied after combining local + MCP tools |
| `max-tool-calls` | Int | 10 | Maximum number of tool calls allowed in the ReAct loop. When reached, the tool list is replaced with an empty list to force termination |

### LlmProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `temperature` | Double | 0.3 | LLM generation temperature. 0.0 (deterministic) to 2.0 (creative) |
| `max-output-tokens` | Int | 4096 | Maximum number of tokens in the LLM response |
| `max-conversation-turns` | Int | 10 | Maximum number of conversation turns to load from Memory |
| `max-context-window-tokens` | Int | 128000 | Context window token budget. `budget = maxContextWindowTokens - systemPromptTokens - maxOutputTokens` |

**Notes:**
- `temperature` can be overridden per request via `AgentCommand.temperature`
- `max-context-window-tokens` should match the actual context window of the LLM model in use (GPT-4: 128K, Claude: 200K, Gemini: 1M)

### RetryProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `max-attempts` | Int | 3 | Maximum number of retry attempts (including the initial attempt) |
| `initial-delay-ms` | Long | 1000 | Wait time before the first retry (ms) |
| `multiplier` | Double | 2.0 | Exponential backoff multiplier. `delay = min(initialDelay * multiplier^attempt, maxDelay)` |
| `max-delay-ms` | Long | 10000 | Maximum wait time (ms). Upper bound for exponential growth |

**Retryable errors (transient):**
- Rate limit (429)
- Timeout
- 5xx server errors
- Connection errors

**Non-retryable:**
- Authentication errors, Context too long, Invalid request
- `CancellationException` -- never retried (preserves structured concurrency)

### GuardProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Enables the Guard pipeline. When `false`, all Guard stages are disabled |
| `rate-limit-per-minute` | Int | 10 | Per-user request limit per minute |
| `rate-limit-per-hour` | Int | 100 | Per-user request limit per hour |
| `max-input-length` | Int | 10000 | Maximum user input length (character count) |
| `injection-detection-enabled` | Boolean | true | Enables prompt injection detection |

**Behavior:**
- `enabled=false`: The Guard bean itself is not created (`@ConditionalOnProperty`)
- `injection-detection-enabled=false`: Only the injection detection stage is disabled; the remaining Guard stages still operate

### ConcurrencyProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `max-concurrent-requests` | Int | 20 | Limits the number of concurrent agent executions. Uses `Semaphore(permits)` |
| `request-timeout-ms` | Long | 30000 | Overall request timeout (ms). Applied via `withTimeout()` |

**Note:** Semaphore wait time is included in the timeout. In other words, a timeout can occur while waiting for the semaphore.

### CacheProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enables response caching. Disabled by default (opt-in) |
| `max-size` | Long | 1000 | Maximum number of cached entries |
| `ttl-minutes` | Long | 60 | Time-to-live for cache entries in minutes |
| `cacheable-temperature` | Double | 0.0 | Only cache responses when the request temperature is at or below this value. Set to `0.0` to cache only deterministic requests; set to `1.0` to cache all requests |

**Behavior:**
- Cache key: SHA-256 hash of `userPrompt + systemPrompt + model + tools + responseFormat`
- Cached before response filters are applied
- Streaming requests are never cached

### CircuitBreakerProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enables the circuit breaker. Disabled by default (opt-in) |
| `failure-threshold` | Int | 5 | Number of consecutive failures before opening the circuit |
| `reset-timeout-ms` | Long | 30000 | Time in ms to wait before transitioning from OPEN to HALF_OPEN |
| `half-open-max-calls` | Int | 1 | Number of trial calls allowed in HALF_OPEN state |

**State transitions:**
- `CLOSED` (normal) -> N consecutive failures -> `OPEN` (all calls rejected)
- `OPEN` -> after `resetTimeoutMs` -> `HALF_OPEN` (trial call allowed)
- `HALF_OPEN` -> trial succeeds -> `CLOSED`
- `HALF_OPEN` -> trial fails -> `OPEN`

### FallbackProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enables graceful degradation. Disabled by default (opt-in) |
| `models` | List&lt;String&gt; | [] | Fallback model names in priority order (e.g., `[openai, anthropic]`) |

**Behavior:**
- Triggered when the primary model fails after retries
- Models are tried sequentially in the order listed
- Requires matching provider beans to be registered (e.g., `SPRING_AI_OPENAI_API_KEY` env var)

### RagProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enables the RAG pipeline. Must be set to `true` for RAG-related beans to be created |
| `similarity-threshold` | Double | 0.7 | Vector search similarity threshold (0.0~1.0) |
| `top-k` | Int | 10 | Number of vector search results |
| `rerank-enabled` | Boolean | true | Enables search result reranking |
| `query-transformer` | String | `passthrough` | Query rewrite mode (`passthrough` or `hyde`) |
| `max-context-tokens` | Int | 4000 | Maximum number of tokens allocated to the RAG context |

---

## Auto-Configuration

### Bean Creation Order and Conditions

Arc Reactor automatically creates all core beans via Spring Boot Auto-Configuration. Every bean has `@ConditionalOnMissingBean` applied, so **if a user-defined bean exists, automatic creation is skipped**.

```
ArcReactorAutoConfiguration
├── Always created
│   ├── toolSelector          → AllToolSelector (selects all tools)
│   ├── errorMessageResolver  → DefaultErrorMessageResolver (English)
│   ├── agentMetrics          → NoOpAgentMetrics (metrics disabled)
│   ├── tokenEstimator        → DefaultTokenEstimator (CJK-aware)
│   ├── conversationManager   → DefaultConversationManager
│   ├── mcpManager            → DefaultMcpManager
│   ├── hookExecutor          → HookExecutor (empty Hook list)
│   └── responseFilterChain   → ResponseFilterChain (empty filter list)
│
├── Conditionally created
│   ├── jdbcMemoryStore       → @ConditionalOnClass(JdbcTemplate) + @ConditionalOnBean(DataSource)
│   ├── memoryStore           → InMemoryMemoryStore (fallback when jdbcMemoryStore is absent)
│   └── agentExecutor         → @ConditionalOnBean(ChatClient) (required!)
│
├── guard.enabled=true (default) → GuardConfiguration
│   ├── rateLimitStage        → DefaultRateLimitStage
│   ├── inputValidationStage  → DefaultInputValidationStage
│   ├── injectionDetectionStage → DefaultInjectionDetectionStage (injection-detection-enabled=true)
│   └── requestGuard          → GuardPipeline(stages)
│
├── cache.enabled=true → CacheConfiguration
│   └── responseCache       → CaffeineResponseCache
│
├── circuit-breaker.enabled=true → CircuitBreakerConfiguration
│   └── circuitBreaker      → DefaultCircuitBreaker
│
├── fallback.enabled=true → FallbackConfiguration
│   └── fallbackStrategy    → ModelFallbackStrategy
│
└── rag.enabled=true → RagConfiguration
    ├── documentRetriever     → SpringAiVectorStoreRetriever (@ConditionalOnBean(VectorStore))
    ├── inMemoryRetriever     → InMemoryDocumentRetriever (fallback when VectorStore is absent)
    ├── documentReranker      → SimpleScoreReranker
    └── ragPipeline           → DefaultRagPipeline
```

### Core Pattern: @ConditionalOnMissingBean

Since every bean uses this annotation, replacing them with custom implementations is straightforward:

```kotlin
// If a user-defined bean exists, automatic creation is skipped
@Bean
fun toolSelector(): ToolSelector = MyCustomToolSelector()

@Bean
fun errorMessageResolver(): ErrorMessageResolver = KoreanErrorMessageResolver()

@Bean
fun agentMetrics(): AgentMetrics = MicrometerAgentMetrics(registry)
```

### MemoryStore Automatic Selection Logic

```
DataSource bean present?
├── YES → JdbcTemplate class present?
│         ├── YES → JdbcMemoryStore (PostgreSQL)
│         └── NO  → InMemoryMemoryStore (fallback)
└── NO  → InMemoryMemoryStore (fallback)
```

No code changes needed -- just add the JDBC dependency in `build.gradle.kts` and configure the DataSource in `application.yml`, and the switch happens automatically.

### AgentExecutor Required Dependency

The `agentExecutor` bean has `@ConditionalOnBean(ChatClient::class)` applied. If no `ChatClient` bean exists, the agent will not be created. A Spring AI LLM provider dependency is required:

```kotlin
// build.gradle.kts — at least one must be enabled
implementation("org.springframework.ai:spring-ai-starter-model-google-genai")
// implementation("org.springframework.ai:spring-ai-starter-model-openai")
// implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
```

### Custom Bean Replacement Examples

```kotlin
@Configuration
class MyConfig {

    // Korean error messages
    @Bean
    fun errorMessageResolver() = ErrorMessageResolver { code, _ ->
        when (code) {
            AgentErrorCode.RATE_LIMITED -> "요청 한도 초과. 잠시 후 다시 시도하세요."
            AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
            else -> code.defaultMessage
        }
    }

    // Micrometer metrics
    @Bean
    fun agentMetrics(registry: MeterRegistry) = MicrometerAgentMetrics(registry)

    // Custom tool selector
    @Bean
    fun toolSelector() = CategoryBasedToolSelector(
        categories = listOf(ToolCategory.SEARCH, ToolCategory.CALCULATION)
    )
}
```

---

## Production Configuration Examples

### High-Traffic Environment

```yaml
arc:
  reactor:
    max-tool-calls: 5            # Stricter loop limit
    concurrency:
      max-concurrent-requests: 50   # Increased concurrent requests
      request-timeout-ms: 60000     # More timeout headroom
    retry:
      max-attempts: 5              # More retry headroom
      max-delay-ms: 30000
    guard:
      rate-limit-per-minute: 30    # Relaxed rate limit
      rate-limit-per-hour: 500
```

### Cost Optimization

```yaml
arc:
  reactor:
    max-tool-calls: 3            # Minimize tool calls
    llm:
      temperature: 0.1           # Deterministic responses
      max-output-tokens: 2048    # Save output tokens
      max-context-window-tokens: 32000  # Reduced context
    rag:
      max-context-tokens: 2000   # Save RAG tokens
      top-k: 5                   # Fewer search results
```

### Security Hardening

```yaml
arc:
  reactor:
    guard:
      enabled: true
      rate-limit-per-minute: 5
      rate-limit-per-hour: 50
      max-input-length: 5000
      injection-detection-enabled: true
    concurrency:
      request-timeout-ms: 15000  # Short timeout
```
