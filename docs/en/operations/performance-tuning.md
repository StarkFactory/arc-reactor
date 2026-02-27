# Performance Tuning Guide

This guide covers the configuration knobs available in Arc Reactor for throughput, latency,
cost, and reliability tuning. All properties live under the `arc.reactor.*` namespace unless
noted otherwise. Defaults are sourced from `AgentProperties.kt` and
`AgentPolicyAndFeatureProperties.kt`.

---

## 1. Concurrency Tuning

Concurrency is managed by a `kotlinx.coroutines.sync.Semaphore` initialized with
`maxConcurrentRequests`. Both the standard and streaming paths share this semaphore.
Each request holds a permit for the full duration of the LLM call and all tool calls.

| Property | Default | Description |
|---|---|---|
| `arc.reactor.concurrency.max-concurrent-requests` | `20` | Maximum simultaneous in-flight requests |
| `arc.reactor.concurrency.request-timeout-ms` | `30000` | Wall-clock deadline per request (ms) |
| `arc.reactor.concurrency.tool-call-timeout-ms` | `15000` | Deadline for each individual tool call (ms) |

**Sizing `max-concurrent-requests`.** The right value depends on the LLM provider's own
throughput limits and the JVM thread pool. Because Arc Reactor is WebFlux / coroutine-based,
holding many permits does not consume OS threads. A value between 20 and 100 is reasonable
for most deployments. Exceeding the provider's own rate limit will produce `RATE_LIMITED`
or `TIMEOUT` errors at the LLM layer.

**Request timeout.** If the LLM provider or a tool server is slow under load, increase
`request-timeout-ms`. Keep in mind that SSE clients will see no data until the first token
arrives, so a long timeout degrades perceived latency. Consider streaming (`/api/chat/stream`)
for user-facing interactions.

**Tool call timeout.** Each tool call runs inside its own `withTimeout` block. MCP tool
servers with cold-start latency may need a higher `tool-call-timeout-ms`. The default 15 s is
intentionally conservative.

**Example — high-throughput internal service:**
```yaml
arc:
  reactor:
    concurrency:
      max-concurrent-requests: 80
      request-timeout-ms: 60000
      tool-call-timeout-ms: 20000
```

---

## 2. LLM Cost Control

LLM cost is a function of token consumption and the number of LLM calls per request. The
following properties limit both dimensions.

| Property | Default | Description |
|---|---|---|
| `arc.reactor.max-tool-calls` | `10` | Maximum tool-call iterations per ReAct loop before forcing a final LLM call without tools |
| `arc.reactor.max-tools-per-request` | `20` | Maximum tool definitions sent to the LLM per request (limits prompt size) |
| `arc.reactor.llm.temperature` | `0.3` | Sampling temperature (0.0 = deterministic, higher = more creative) |
| `arc.reactor.llm.max-output-tokens` | `4096` | Token cap on the LLM response |
| `arc.reactor.llm.max-context-window-tokens` | `128000` | Token budget for the full prompt (conversation history + system prompt + user message) |
| `arc.reactor.llm.max-conversation-turns` | `10` | Maximum conversation history turns retained per session |
| `arc.reactor.llm.google-search-retrieval-enabled` | `false` | Enables Gemini Google Search grounding (adds cost per call) |

**Controlling tool-call iterations.** The ReAct loop calls the LLM once per reasoning step.
If the LLM calls tools on each step, cost scales with `max-tool-calls`. For cost-sensitive
deployments, reduce this to 3–5 and provide a focused system prompt. When `max-tool-calls`
is reached, `activeTools` is set to an empty list, forcing the LLM to produce a final
answer without further tool calls (this is the mandatory behavior to prevent infinite loops).

**Limiting tool definitions.** Every tool definition in the prompt consumes tokens. Use
`max-tools-per-request` to cap the prompt size when many tools are registered. Combined with
semantic or keyword tool selection (`arc.reactor.tool-selection.strategy`), only the most
relevant tools reach the LLM.

**Temperature.** The default `0.3` balances consistency with mild creativity. For pipelines
requiring deterministic output (structured JSON/YAML responses, data extraction), set
`temperature: 0.0`. Response caching (see section 5) only applies at or below
`cache.cacheable-temperature` (default `0.0`).

**Output token cap.** Lowering `max-output-tokens` reduces both latency and cost at the
expense of response completeness. Ensure `max-context-window-tokens` is always greater than
`max-output-tokens`; the executor enforces this invariant at startup and will fail to start
if violated.

**Example — cost-optimized configuration:**
```yaml
arc:
  reactor:
    max-tool-calls: 5
    max-tools-per-request: 10
    llm:
      temperature: 0.0
      max-output-tokens: 1024
      max-conversation-turns: 5
```

---

## 3. Memory and Context Management

Context window usage directly drives LLM cost. Arc Reactor provides two layers of context
management: token-based trimming and optional hierarchical memory summarization.

### Token-based Trimming

The `ConversationMessageTrimmer` runs before each LLM call. It estimates token usage and
trims the oldest conversation messages until the total fits within the budget:

```
budget = max-context-window-tokens - max-output-tokens
```

The most-recent user message is always protected from trimming. Tool call / tool response
message pairs are always removed together to preserve message integrity.

| Property | Default | Description |
|---|---|---|
| `arc.reactor.llm.max-context-window-tokens` | `128000` | Total token budget for the prompt |
| `arc.reactor.llm.max-output-tokens` | `4096` | Reserved tokens for the LLM response |
| `arc.reactor.llm.max-conversation-turns` | `10` | Max turns kept in memory (trimmed FIFO) |

### Hierarchical Memory Summarization

For long-running sessions, conversation history grows until it is trimmed (losing early
context). Memory summarization compresses old messages into a structured facts + narrative
block while preserving recent turns verbatim.

| Property | Default | Description |
|---|---|---|
| `arc.reactor.memory.summary.enabled` | `false` | Enable hierarchical summarization (opt-in) |
| `arc.reactor.memory.summary.trigger-message-count` | `20` | Minimum message count before summarization runs |
| `arc.reactor.memory.summary.recent-message-count` | `10` | Recent turns preserved verbatim |
| `arc.reactor.memory.summary.llm-model` | `null` | LLM provider for summarization (`null` = default provider) |
| `arc.reactor.memory.summary.max-narrative-tokens` | `500` | Token budget for the narrative summary block |

**Note.** Summarization adds one LLM call per trigger. Use a cheaper model for summarization
(e.g. `gemini-2.0-flash`) to keep summary cost low.

**Example — long-session configuration:**
```yaml
arc:
  reactor:
    llm:
      max-context-window-tokens: 128000
      max-output-tokens: 4096
      max-conversation-turns: 30
    memory:
      summary:
        enabled: true
        trigger-message-count: 20
        recent-message-count: 8
        llm-model: gemini-2.0-flash
        max-narrative-tokens: 500
```

---

## 4. Guard Rate Limits

The Guard enforces per-user sliding-window rate limits. Both limits must pass; a request that
exceeds either limit returns `RATE_LIMITED`.

| Property | Default | Description |
|---|---|---|
| `arc.reactor.guard.rate-limit-per-minute` | `10` | Max requests per user per minute |
| `arc.reactor.guard.rate-limit-per-hour` | `100` | Max requests per user per hour |
| `arc.reactor.guard.tenant-rate-limits` | `{}` | Per-tenant overrides (map of tenant ID → limits) |
| `arc.reactor.boundaries.input-max-chars` | `5000` | Maximum input length in characters |
| `arc.reactor.boundaries.input-min-chars` | `1` | Minimum input length in characters |

**Tenant-specific rate limits** allow differentiated service levels without separate
deployments:

```yaml
arc:
  reactor:
    guard:
      rate-limit-per-minute: 10
      rate-limit-per-hour: 100
      tenant-rate-limits:
        premium-tenant:
          per-minute: 60
          per-hour: 1000
        free-tier:
          per-minute: 5
          per-hour: 50
```

**Input length.** Increase `input-max-chars` cautiously; long inputs consume tokens directly.
The default 5 000-character limit prevents accidental token budget exhaustion from user paste
operations.

**Example — production rate limits:**
```yaml
arc:
  reactor:
    guard:
      rate-limit-per-minute: 20
      rate-limit-per-hour: 200
    boundaries:
      input-max-chars: 10000
      input-min-chars: 1
```

---

## 5. Multimodal Limits

File uploads via `POST /api/chat/multipart` and media URL references in JSON requests are
gated by `MultimodalProperties`.

| Property | Default | Description |
|---|---|---|
| `arc.reactor.multimodal.enabled` | `true` | Enable file upload and media URL support |
| `arc.reactor.multimodal.max-file-size-bytes` | `10485760` (10 MB) | Maximum size per uploaded file |
| `arc.reactor.multimodal.max-files-per-request` | `5` | Maximum files per multipart request |

**Spring WebFlux in-memory buffer.** Spring WebFlux buffers multipart data in memory before
Arc Reactor's per-file check runs. By default, the buffer limit may be lower than
`max-file-size-bytes`. Uncomment and set the following in `application.yml` if uploads fail
before reaching the Arc Reactor size check:

```yaml
spring:
  webflux:
    multipart:
      max-in-memory-size: 10MB
```

**Disabling multimodal** eliminates the file parsing code path entirely and reduces per-request
overhead for text-only deployments:

```yaml
arc:
  reactor:
    multimodal:
      enabled: false
```

---

## 6. Database Connection Pooling

Arc Reactor uses Spring Boot's default HikariCP connection pool when a `DataSource` is
configured (PostgreSQL for production; H2 for tests). HikariCP manages connections shared
across all JDBC-backed stores (conversation memory, MCP server registry, etc.).

HikariCP defaults that matter for performance:

| HikariCP Property | Spring Boot Default | Recommended (prod) |
|---|---|---|
| `maximum-pool-size` | `10` | `20–50` depending on DB server |
| `minimum-idle` | same as `maximum-pool-size` | Same (avoid pool shrinkage) |
| `connection-timeout` | `30000` ms | `5000` ms (fail fast) |
| `idle-timeout` | `600000` ms (10 min) | `300000` ms (5 min) |
| `max-lifetime` | `1800000` ms (30 min) | `1800000` ms |
| `keepalive-time` | disabled | `60000` ms (prevent stale connections) |

**Example production HikariCP configuration:**
```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    hikari:
      maximum-pool-size: 30
      minimum-idle: 30
      connection-timeout: 5000
      idle-timeout: 300000
      max-lifetime: 1800000
      keepalive-time: 60000
```

**Sizing the pool.** The WebFlux / coroutine stack is non-blocking for HTTP processing, but
JDBC calls use `Dispatchers.IO` (blocking thread pool, default size 64). A pool of 20–30
connections is appropriate for most deployments. Increasing beyond the PostgreSQL
`max_connections` limit will cause connection acquisition timeouts.

---

## 7. JVM Tuning

Arc Reactor runs on JDK 21 with Spring WebFlux (Project Reactor + Kotlin coroutines). The
application is I/O-bound rather than CPU-bound, so GC tuning has moderate impact compared
to concurrency and connection settings.

### Recommended JVM Flags

```bash
# Production baseline (JDK 21, G1GC)
JAVA_OPTS="-XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:InitiatingHeapOccupancyPercent=35 \
  -Xms512m \
  -Xmx2g \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/arc-reactor/heapdump.hprof"
```

**Notes:**
- **G1GC** is the default for JDK 21 and performs well for this workload. ZGC is an
  alternative for sub-millisecond pause requirements but adds overhead.
- **`-XX:MaxGCPauseMillis=100`** — keeps GC pauses below the tool-call timeout in typical
  scenarios.
- **`-XX:+ExitOnOutOfMemoryError`** — forces container restart on OOM rather than limping
  along in a degraded state.
- **Heap sizing.** Start with `-Xmx2g` and observe. Conversation history stored in the
  in-memory `MemoryStore` (default) grows without bound; monitor heap usage over time and
  set session TTLs appropriately.

### Virtual Threads (JDK 21)

Spring Boot 3.2+ supports virtual threads via `spring.threads.virtual.enabled=true`. Because
Arc Reactor's blocking work is confined to JDBC (`Dispatchers.IO`) and the reactive pipeline
handles HTTP, enabling virtual threads provides marginal benefit for Arc Reactor specifically.
It may help if you have custom blocking components. Test before enabling in production.

```yaml
spring:
  threads:
    virtual:
      enabled: false  # evaluate for your workload
```

---

## 8. Retry and Circuit Breaker

### Retry

The retry executor wraps LLM calls with exponential back-off for transient errors (rate
limits, timeouts, connection failures, 5xx responses). Tool call failures are not retried
at the ReAct loop level; only the LLM call itself is retried.

| Property | Default | Description |
|---|---|---|
| `arc.reactor.retry.max-attempts` | `3` | Maximum retry attempts per LLM call |
| `arc.reactor.retry.initial-delay-ms` | `1000` | Initial wait before first retry |
| `arc.reactor.retry.multiplier` | `2.0` | Back-off multiplier between retries |
| `arc.reactor.retry.max-delay-ms` | `10000` | Maximum wait between retries |

With defaults, the retry sequence is: immediate call → 1 s wait → 2 s wait → fail. This
keeps the total retry overhead under `request-timeout-ms` for most cases.

### Circuit Breaker

The circuit breaker is disabled by default (opt-in).

| Property | Default | Description |
|---|---|---|
| `arc.reactor.circuit-breaker.enabled` | `false` | Enable circuit breaker |
| `arc.reactor.circuit-breaker.failure-threshold` | `5` | Consecutive failures before opening |
| `arc.reactor.circuit-breaker.reset-timeout-ms` | `30000` | Time in OPEN state before HALF_OPEN |
| `arc.reactor.circuit-breaker.half-open-max-calls` | `1` | Trial calls in HALF_OPEN state |

**Example — production resilience:**
```yaml
arc:
  reactor:
    retry:
      max-attempts: 3
      initial-delay-ms: 1000
      multiplier: 2.0
      max-delay-ms: 10000
    circuit-breaker:
      enabled: true
      failure-threshold: 5
      reset-timeout-ms: 30000
      half-open-max-calls: 1
```

---

## 9. Response Caching

Response caching avoids redundant LLM calls for identical requests. Only deterministic
requests (temperature at or below `cacheable-temperature`) are cached.

| Property | Default | Description |
|---|---|---|
| `arc.reactor.cache.enabled` | `false` | Enable response caching (opt-in) |
| `arc.reactor.cache.max-size` | `1000` | Maximum cached entries (LRU eviction) |
| `arc.reactor.cache.ttl-minutes` | `60` | Cache entry TTL in minutes |
| `arc.reactor.cache.cacheable-temperature` | `0.0` | Only cache when `temperature <= this value` |

**Caching is effective for** FAQ bots, knowledge-base Q&A, and any workload where many
users ask semantically identical questions. It has no benefit for conversational agents
where each request is unique.

```yaml
arc:
  reactor:
    llm:
      temperature: 0.0
    cache:
      enabled: true
      max-size: 5000
      ttl-minutes: 120
      cacheable-temperature: 0.0
```

---

## 10. Recommended Configurations by Environment

### Development

Prioritizes fast iteration: short timeouts, low concurrency, verbose logging.

```yaml
arc:
  reactor:
    max-tool-calls: 5
    max-tools-per-request: 20
    llm:
      temperature: 0.7
      max-output-tokens: 2048
      max-conversation-turns: 5
    concurrency:
      max-concurrent-requests: 5
      request-timeout-ms: 60000
      tool-call-timeout-ms: 30000
    guard:
      rate-limit-per-minute: 60
      rate-limit-per-hour: 1000
    boundaries:
      input-max-chars: 10000

logging:
  level:
    com.arc.reactor: DEBUG
```

### Production

Balances throughput, cost, and resilience. Circuit breaker enabled. Conservative rate limits.

```yaml
arc:
  reactor:
    max-tool-calls: 10
    max-tools-per-request: 15
    llm:
      temperature: 0.3
      max-output-tokens: 4096
      max-context-window-tokens: 128000
      max-conversation-turns: 10
    concurrency:
      max-concurrent-requests: 40
      request-timeout-ms: 30000
      tool-call-timeout-ms: 15000
    guard:
      enabled: true
      rate-limit-per-minute: 20
      rate-limit-per-hour: 200
    boundaries:
      input-min-chars: 1
      input-max-chars: 10000
    retry:
      max-attempts: 3
      initial-delay-ms: 1000
      multiplier: 2.0
      max-delay-ms: 10000
    circuit-breaker:
      enabled: true
      failure-threshold: 5
      reset-timeout-ms: 30000

spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      minimum-idle: 30
      connection-timeout: 5000
      keepalive-time: 60000

logging:
  level:
    com.arc.reactor: INFO
```

### High-Throughput / Batch

Maximizes parallelism. Rate limits relaxed. Caching enabled. Memory summarization for long
sessions. Use a dedicated deployment separate from the interactive API.

```yaml
arc:
  reactor:
    max-tool-calls: 3
    max-tools-per-request: 10
    llm:
      temperature: 0.0
      max-output-tokens: 1024
      max-conversation-turns: 3
    concurrency:
      max-concurrent-requests: 80
      request-timeout-ms: 120000
      tool-call-timeout-ms: 30000
    guard:
      rate-limit-per-minute: 300
      rate-limit-per-hour: 10000
    boundaries:
      input-max-chars: 20000
    cache:
      enabled: true
      max-size: 10000
      ttl-minutes: 240
      cacheable-temperature: 0.0
    retry:
      max-attempts: 5
      initial-delay-ms: 500
      multiplier: 1.5
      max-delay-ms: 5000
    circuit-breaker:
      enabled: true
      failure-threshold: 10
      reset-timeout-ms: 60000

spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 50

logging:
  level:
    com.arc.reactor: WARN
```

---

## Key Constraints

- `max-context-window-tokens` must be strictly greater than `max-output-tokens`. The executor
  enforces this at startup and will throw `IllegalArgumentException` if violated.
- `max-tool-calls` reaching its limit forces an empty tool list on the next LLM call. Setting
  this to 0 effectively disables tool calling (STANDARD mode only).
- Retry introduces additional latency equal to the sum of delay intervals. With defaults,
  3 attempts add up to 3 s of back-off. Ensure `request-timeout-ms` is large enough to
  accommodate retries: minimum recommended is `initial-delay-ms * (multiplier^max-attempts)`.
