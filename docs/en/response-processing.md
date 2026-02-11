# Response Post-Processing

## Overview

The Response Post-Processing pipeline applies filters to agent responses before returning them to the caller. This enables content transformation, truncation, sanitization, and other processing without modifying the core agent executor.

Filters are applied to **non-streaming `execute()` results only**. Streaming responses (`executeStream()`) are delivered in real-time and bypass the filter chain.

---

## Architecture

```
LLM Response → Structured Output Validation → Response Filter Chain → Save to Memory → Hooks → Return
```

The filter chain runs after structured output validation (JSON/YAML) but before:
- Conversation memory save
- AfterAgentComplete hook execution
- Metrics recording

This means hooks and memory receive the **filtered** content.

---

## ResponseFilter Interface

```kotlin
interface ResponseFilter {
    val order: Int get() = 100

    suspend fun filter(content: String, context: ResponseFilterContext): String
}

data class ResponseFilterContext(
    val command: AgentCommand,
    val toolsUsed: List<String>,
    val durationMs: Long
)
```

### Key Rules

- **Ordering**: Lower `order` values execute first. Built-in filters use 1-99, custom filters should use 100+.
- **Fail-open**: If a filter throws an exception, it is logged and skipped. The chain continues with the previous content.
- **CancellationException**: Always rethrown (preserves structured concurrency).
- **Idempotent**: Filters should be safe to apply multiple times.

---

## Built-in Filters

### MaxLengthResponseFilter

Truncates responses that exceed a configured character limit.

```yaml
arc:
  reactor:
    response:
      max-length: 10000  # 0 = unlimited (default)
```

When truncation occurs, the response ends with:
```
[Response truncated]
```

Order: `10` (runs early, before custom filters)

---

## Configuration

```yaml
arc:
  reactor:
    response:
      max-length: 0          # Max response chars. 0 = unlimited (default)
      filters-enabled: true   # Enable/disable filter chain (default: true)
```

| Property | Default | Description |
|----------|---------|-------------|
| `max-length` | `0` | Maximum response characters. 0 = no limit |
| `filters-enabled` | `true` | Master switch for the filter chain |

---

## Custom Filter Example

### 1. Implement ResponseFilter

```kotlin
class KeywordRedactionFilter(
    private val blocklist: Set<String>
) : ResponseFilter {
    override val order = 110  // After built-in filters

    override suspend fun filter(content: String, context: ResponseFilterContext): String {
        var result = content
        for (keyword in blocklist) {
            result = result.replace(keyword, "[REDACTED]", ignoreCase = true)
        }
        return result
    }
}
```

### 2. Register as Bean

```kotlin
@Bean
fun keywordRedactionFilter(): ResponseFilter {
    return KeywordRedactionFilter(setOf("secret-project", "internal-code"))
}
```

The filter is automatically picked up by `ResponseFilterChain` via Spring's `ObjectProvider<ResponseFilter>`.

### 3. Override the Chain (optional)

To fully replace the default chain:

```kotlin
@Bean
fun responseFilterChain(): ResponseFilterChain {
    return ResponseFilterChain(listOf(
        MaxLengthResponseFilter(maxLength = 5000),
        KeywordRedactionFilter(setOf("secret"))
    ))
}
```

Since `@ConditionalOnMissingBean` is used, your custom bean takes precedence.

---

## Streaming Behavior

Response filters are **not applied** to streaming responses because:
1. Streaming delivers tokens incrementally — the full content isn't available until the stream ends
2. Applying filters mid-stream could produce inconsistent results
3. Performance: streaming prioritizes low-latency delivery

For streaming-specific processing, use `AfterAgentCompleteHook` for post-stream analysis.

---

## Filter Chain Execution

```
Input Content
    │
    ▼
┌─────────────────────────┐
│ MaxLengthResponseFilter │  order=10
│   (truncate if needed)  │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Custom Filter A        │  order=100
│   (e.g., redaction)     │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Custom Filter B        │  order=200
│   (e.g., footer append) │
└────────────┬────────────┘
             │
             ▼
        Filtered Content
```

If any filter fails, the chain logs the error and passes the **unmodified content** to the next filter.

---

## Response Caching

### Overview

Response caching stores LLM responses for identical requests, avoiding redundant API calls. This is useful for deterministic queries (temperature=0) where the same input always produces the same output.

Caching is **opt-in** and **disabled by default**.

### How It Works

```
Request arrives
    │
    ▼
Is caching enabled? ──No──▶ Call LLM directly
    │
   Yes
    │
    ▼
Is temperature ≤ cacheableTemperature? ──No──▶ Call LLM (non-deterministic)
    │
   Yes
    │
    ▼
Build cache key (SHA-256)
    │
    ▼
Cache hit? ──Yes──▶ Return cached response (no LLM call)
    │
    No
    │
    ▼
Call LLM → Store result in cache → Return response
```

### Cache Key Strategy

The cache key is a SHA-256 hash of:
- System prompt
- User prompt
- Sorted tool names (order-independent)
- Model name

Two requests with the same inputs produce the same key, regardless of tool list ordering.

### Configuration

```yaml
arc:
  reactor:
    cache:
      enabled: true               # Enable response caching (default: false)
      max-size: 1000              # Maximum cached entries (default: 1000)
      ttl-minutes: 60             # Time-to-live per entry (default: 60)
      cacheable-temperature: 0.0  # Max temperature for caching (default: 0.0)
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Enable/disable response caching |
| `max-size` | `1000` | Maximum number of cached entries |
| `ttl-minutes` | `60` | Time-to-live in minutes per entry |
| `cacheable-temperature` | `0.0` | Only cache responses when temperature ≤ this value |

### Temperature-Based Eligibility

Responses are only cached when the request temperature is at or below `cacheable-temperature`:

- `temperature=0.0` with `cacheable-temperature=0.0` → **cached** (deterministic)
- `temperature=0.3` with `cacheable-temperature=0.5` → **cached** (within threshold)
- `temperature=0.8` with `cacheable-temperature=0.5` → **not cached** (non-deterministic)

If no temperature is set on the command, the default from `arc.reactor.llm.temperature` is used.

### Implementations

| Implementation | Description |
|----------------|-------------|
| `CaffeineResponseCache` | Caffeine-backed cache with TTL and max size. Registered when `cache.enabled=true` |
| `NoOpResponseCache` | No-op implementation. All operations are no-ops |

### Custom Cache Implementation

Override the default cache by providing your own `ResponseCache` bean:

```kotlin
@Bean
fun responseCache(): ResponseCache {
    return MyRedisResponseCache(redisTemplate)
}
```

Since `@ConditionalOnMissingBean` is used, your custom bean takes precedence.

### Programmatic Cache Invalidation

```kotlin
@Autowired(required = false)
private val responseCache: ResponseCache? = null

fun clearCache() {
    responseCache?.invalidateAll()
}
```

### Caching and Streaming

Response caching applies to **non-streaming `execute()` only**. Streaming responses (`executeStream()`) are not cached because they deliver tokens incrementally.
