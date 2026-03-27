# Configuration Reference & Auto-Configuration

> **Key files:** `AgentProperties.kt`, `AgentPolicyAndFeatureProperties.kt`, `ArcReactorAutoConfiguration.kt`
> This document covers all configuration options and the Spring Boot auto-configuration mechanism in Arc Reactor.
> New fork? Start with [configuration-quickstart.md](configuration-quickstart.md) first.

## Full Configuration Structure

```yaml
arc:
  reactor:
    max-tools-per-request: 30    # Max tools per request
    max-tool-calls: 10           # Max tool calls in the ReAct loop

    llm:                         # LLM call settings
      default-provider: gemini
      temperature: 0.1
      max-output-tokens: 4096
      max-conversation-turns: 10
      max-context-window-tokens: 128000
      google-search-retrieval-enabled: false
      prompt-caching:            # Anthropic prompt caching
        enabled: false
        provider: anthropic
        cache-system-prompt: true
        cache-tools: true
        min-cacheable-tokens: 1024

    retry:                       # LLM retry settings
      max-attempts: 3
      initial-delay-ms: 200
      multiplier: 2.0
      max-delay-ms: 10000

    guard:                       # Guard pipeline settings
      enabled: true
      rate-limit-per-minute: 20
      rate-limit-per-hour: 200
      injection-detection-enabled: true
      unicode-normalization-enabled: true
      max-zero-width-ratio: 0.1
      classification-enabled: false
      canary-token-enabled: false
      tool-output-sanitization-enabled: false
      audit-enabled: true
      topic-drift-enabled: false

    boundaries:                  # Input/output boundary checks
      input-min-chars: 1
      input-max-chars: 10000
      system-prompt-max-chars: 50000
      output-min-chars: 0
      output-max-chars: 0
      output-min-violation-mode: warn

    rag:                         # RAG pipeline settings
      enabled: false
      similarity-threshold: 0.65
      top-k: 5
      rerank-enabled: false
      query-transformer: passthrough
      max-context-tokens: 4000
      retrieval-timeout-ms: 3000
      chunking:
        enabled: false
        chunk-size: 512
        min-chunk-threshold: 512
        overlap: 50
      hybrid:
        enabled: false
        bm25-weight: 0.5
        vector-weight: 0.5
        rrf-k: 60.0
      parent-retrieval:
        enabled: false
        window-size: 1
      compression:
        enabled: false
        min-content-length: 200
      adaptive-routing:
        enabled: true
        timeout-ms: 3000
        complex-top-k: 15
      ingestion:
        enabled: false
        require-review: true

    concurrency:                 # Concurrency control
      max-concurrent-requests: 20
      request-timeout-ms: 30000
      tool-call-timeout-ms: 15000

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

    tool-selection:              # Tool selection strategy
      strategy: semantic
      similarity-threshold: 0.3
      max-results: 10

    approval:                    # Human-in-the-Loop approval (opt-in)
      enabled: false
      timeout-ms: 300000
      tool-names: []

    tool-policy:                 # Tool policy enforcement (opt-in)
      enabled: false
      write-tool-names: []
      deny-write-channels: [slack]

    auth:                        # Authentication settings
      jwt-secret: ""
      default-tenant-id: default
      token-revocation-store: memory  # memory | jdbc | redis
      token-revocation-redis-key-prefix: "arc:auth:revoked"
      public-actuator-health: true    # prod profile overrides to false

    error-report:                # Error report agent (opt-in)
      enabled: false
      max-concurrent-requests: 5
      request-timeout-ms: 10000
      max-tool-calls: 3

    output-guard:                # Output guard (enabled by default)
      enabled: true
      pii-masking-enabled: true
      dynamic-rules-enabled: true
      dynamic-rules-refresh-ms: 3000

    scheduler:                   # Dynamic scheduler (opt-in)
      enabled: false
      thread-pool-size: 5
      default-execution-timeout-ms: 300000
      max-executions-per-job: 100

    intent:                      # Intent classification (opt-in)
      enabled: false
      confidence-threshold: 0.6
      rule-confidence-threshold: 0.8

    webhook:                     # Webhook notifications (opt-in)
      enabled: false
      url: ""
      timeout-ms: 5000
      include-conversation: false

    multimodal:                  # File upload / media URL
      enabled: true
      max-file-size-bytes: 10485760
      max-files-per-request: 5

    tracing:                     # Distributed tracing
      enabled: true
      service-name: arc-reactor
      include-user-id: false

    citation:                    # Citation auto-formatting (opt-in)
      enabled: false
      format: markdown

    tool-result-cache:           # Tool result caching (enabled by default)
      enabled: true
      ttl-seconds: 60
      max-size: 200

    tool-enrichment:             # Tool parameter enrichment
      requester-aware-tool-names: []

    memory:                      # Conversation memory
      summary:
        enabled: false
        trigger-message-count: 20
        recent-message-count: 10
        max-narrative-tokens: 500
      user:
        enabled: false
        inject-into-prompt: false
        max-prompt-injection-chars: 1000
        max-recent-topics: 10

    response:                    # Response post-processing
      max-length: 0
      filters-enabled: true

    cors:                        # CORS (opt-in)
      enabled: false
      allowed-origins: ["http://localhost:3000"]
      allowed-methods: ["GET","POST","PUT","DELETE","OPTIONS"]
      allowed-headers: ["*"]
      allow-credentials: false
      max-age: 3600

    security-headers:            # Security headers
      enabled: true

    mcp:                         # MCP runtime settings
      connection-timeout-ms: 30000
      allow-private-addresses: false
      security:
        allowed-server-names: []   # env: ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES
        max-tool-output-length: 50000
      reconnection:
        enabled: true
        max-attempts: 5
        initial-delay-ms: 5000
        multiplier: 2.0
        max-delay-ms: 60000

    prompt-lab:                  # Prompt Lab (opt-in)
      enabled: false
      max-concurrent-experiments: 3
      max-queries-per-experiment: 100
      candidate-count: 3
      experiment-timeout-ms: 600000

    api-version:                 # API version contract (header-based)
      enabled: true
      current: v1
      supported: v1
```

## Configuration Groups in Detail

### AgentProperties (Root)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `max-tools-per-request` | Int | 30 | Maximum number of tools available per request. `take(n)` is applied after combining local + MCP tools |
| `max-tool-calls` | Int | 10 | Maximum number of tool calls allowed in the ReAct loop. When reached, the tool list is replaced with an empty list to force termination |

### LlmProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `default-provider` | String | `gemini` | Default LLM provider (e.g., `gemini`, `openai`, `anthropic`) |
| `temperature` | Double | 0.1 | LLM generation temperature. 0.0 (deterministic) to 2.0 (creative) |
| `max-output-tokens` | Int | 4096 | Maximum number of tokens in the LLM response |
| `top-p` | Double | null | Nucleus sampling. `null` uses provider default |
| `frequency-penalty` | Double | null | Frequency penalty. `null` uses provider default |
| `presence-penalty` | Double | null | Presence penalty. `null` uses provider default |
| `google-search-retrieval-enabled` | Boolean | false | Enable Gemini Google Search retrieval grounding. Off by default to avoid unintended external retrieval |
| `max-conversation-turns` | Int | 10 | Maximum number of conversation turns to load from Memory |
| `max-context-window-tokens` | Int | 128000 | Context window token budget. `budget = maxContextWindowTokens - systemPromptTokens - maxOutputTokens` |

**Notes:**
- `temperature` can be overridden per request via `AgentCommand.temperature`
- `max-context-window-tokens` should match the actual context window of the LLM model in use (GPT-4: 128K, Claude: 200K, Gemini: 1M)

### PromptCachingProperties

Nested under `arc.reactor.llm.prompt-caching`. Only supported for the `anthropic` provider.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable Anthropic prompt caching (opt-in) |
| `provider` | String | `anthropic` | LLM provider to apply caching for |
| `cache-system-prompt` | Boolean | true | Mark the system prompt for caching |
| `cache-tools` | Boolean | true | Mark tool definitions for caching |
| `min-cacheable-tokens` | Int | 1024 | Minimum estimated token count before marking content for caching |

**Behavior:**
- Repeating content (system prompts, tool definitions) is marked with `cache_control: {"type": "ephemeral"}` so Anthropic can reuse cached tokens
- Can reduce prompt token costs by 80-90% for requests sharing a common prefix
- Requests to non-Anthropic providers are unaffected

### RetryProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `max-attempts` | Int | 3 | Maximum number of retry attempts (including the initial attempt) |
| `initial-delay-ms` | Long | 200 | Wait time before the first retry (ms) |
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
| `rate-limit-per-minute` | Int | 20 | Per-user request limit per minute |
| `rate-limit-per-hour` | Int | 200 | Per-user request limit per hour |
| `injection-detection-enabled` | Boolean | true | Enables prompt injection detection |
| `unicode-normalization-enabled` | Boolean | true | Enables NFKC normalization, zero-width character stripping, and homoglyph detection |
| `max-zero-width-ratio` | Double | 0.1 | Maximum zero-width character ratio before rejection (0.0-1.0) |
| `classification-enabled` | Boolean | false | Enables rule-based + optional LLM input classification |
| `classification-llm-enabled` | Boolean | false | Enables LLM-based classification (requires `classification-enabled`) |
| `canary-token-enabled` | Boolean | false | Enables canary token for system prompt leakage detection |
| `tool-output-sanitization-enabled` | Boolean | false | Enables tool output sanitization |
| `audit-enabled` | Boolean | true | Enables guard audit trail |
| `topic-drift-enabled` | Boolean | false | Enables topic drift detection (Crescendo attack defense) |
| `canary-seed` | String | `arc-reactor-canary` | Canary token seed (override per deployment for unique tokens) |

**Behavior:**
- `enabled=false`: The Guard bean itself is not created (`@ConditionalOnProperty`)
- `injection-detection-enabled=false`: Only the injection detection stage is disabled; the remaining Guard stages still operate
- Tenant-specific rate limits can be configured via `tenant-rate-limits` map

### BoundaryProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `input-min-chars` | Int | 1 | Minimum user input length (character count) |
| `input-max-chars` | Int | 10000 | Maximum user input length (character count) |
| `system-prompt-max-chars` | Int | 50000 | Maximum system prompt length. `0` means unlimited |
| `output-min-chars` | Int | 0 | Minimum output length. `0` means disabled |
| `output-max-chars` | Int | 0 | Maximum output length. `0` means disabled |
| `output-min-violation-mode` | Enum | `WARN` | Policy when output is below `output-min-chars`: `WARN`, `RETRY_ONCE`, or `FAIL` |

### ConcurrencyProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `max-concurrent-requests` | Int | 20 | Limits the number of concurrent agent executions. Uses `Semaphore(permits)` |
| `request-timeout-ms` | Long | 30000 | Overall request timeout (ms). Applied via `withTimeout()` |
| `tool-call-timeout-ms` | Long | 15000 | Per-tool call timeout (ms) |

**Note:** Semaphore wait time is included in the timeout. In other words, a timeout can occur while waiting for the semaphore.

### RagProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enables the RAG pipeline. Must be set to `true` for RAG-related beans to be created |
| `similarity-threshold` | Double | 0.65 | Vector search similarity threshold (0.0~1.0) |
| `top-k` | Int | 5 | Number of vector search results |
| `rerank-enabled` | Boolean | false | Enables search result reranking |
| `query-transformer` | String | `passthrough` | Query rewrite mode (`passthrough`, `hyde`, or `decomposition`) |
| `max-context-tokens` | Int | 4000 | Maximum number of tokens allocated to the RAG context |
| `retrieval-timeout-ms` | Long | 3000 | Retrieval timeout (ms). Prevents thread-pool exhaustion when vector DB is unresponsive |

### RagChunkingProperties

Nested under `arc.reactor.rag.chunking`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable document chunking (opt-in) |
| `chunk-size` | Int | 512 | Target chunk size in tokens (approx 4 chars = 1 token) |
| `min-chunk-size-chars` | Int | 350 | Minimum chunk size in characters to prevent overly small chunks |
| `min-chunk-threshold` | Int | 512 | Documents with estimated tokens at or below this threshold are not split |
| `overlap` | Int | 50 | Overlap tokens between adjacent chunks for context preservation |
| `keep-separator` | Boolean | true | Preserve paragraph/sentence separators when splitting |
| `max-num-chunks` | Int | 100 | Maximum number of chunks per document |

### RagHybridProperties

Nested under `arc.reactor.rag.hybrid`. BM25 keyword scores are fused with vector similarity scores via Reciprocal Rank Fusion (RRF).

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable hybrid BM25 + vector search (requires `rag.enabled=true`) |
| `bm25-weight` | Double | 0.5 | RRF weight for BM25 ranks (0.0-1.0) |
| `vector-weight` | Double | 0.5 | RRF weight for vector search ranks (0.0-1.0) |
| `rrf-k` | Double | 60.0 | RRF smoothing constant K -- higher value reduces rank-position sensitivity |
| `bm25-k1` | Double | 1.5 | BM25 term-frequency saturation parameter |
| `bm25-b` | Double | 0.75 | BM25 length normalization parameter |

### RagParentRetrievalProperties

Nested under `arc.reactor.rag.parent-retrieval`. Expands chunked search results with adjacent chunks from the same parent document.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable parent document retrieval (requires `rag.enabled=true`) |
| `window-size` | Int | 1 | Number of adjacent chunks to include before and after each hit |

### RagCompressionProperties

Nested under `arc.reactor.rag.compression`. Based on RECOMP (Xu et al., 2024).

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable contextual compression (opt-in) |
| `min-content-length` | Int | 200 | Documents shorter than this (in chars) skip compression |

### AdaptiveRoutingProperties

Nested under `arc.reactor.rag.adaptive-routing`. Based on [Adaptive-RAG (Jeong et al., 2024)](https://arxiv.org/abs/2403.14403).

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Enable adaptive query routing. Skips RAG for simple queries |
| `timeout-ms` | Long | 3000 | Classification timeout (ms) |
| `complex-top-k` | Int | 15 | topK override for COMPLEX queries |

### RagIngestionProperties

Nested under `arc.reactor.rag.ingestion`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable ingestion candidate capture |
| `require-review` | Boolean | true | Whether admin review is required before vector ingestion |
| `allowed-channels` | Set | [] | Allowed channels for auto-capture. Empty = capture from all channels |
| `min-query-chars` | Int | 10 | Minimum query length to be considered knowledge-worthy |
| `min-response-chars` | Int | 20 | Minimum response length to be considered knowledge-worthy |
| `blocked-patterns` | Set | [] | Regex patterns that block capture when matched |
| `dynamic.enabled` | Boolean | false | Enable DB-backed policy override through admin APIs |
| `dynamic.refresh-ms` | Long | 10000 | Cache refresh interval for dynamic policy (ms) |

### ToolSelectionProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `strategy` | String | `semantic` | Selection strategy: `all`, `keyword`, or `semantic` |
| `similarity-threshold` | Double | 0.3 | Minimum cosine similarity threshold for semantic selection |
| `max-results` | Int | 10 | Maximum number of tools to return from semantic selection |

### ApprovalProperties

Human-in-the-Loop approval for side-effecting tool calls.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable Human-in-the-Loop approval (opt-in) |
| `timeout-ms` | Long | 300000 | Default approval timeout in milliseconds (5 minutes) |
| `resolved-retention-ms` | Long | 604800000 | Retention for resolved approvals before cleanup (7 days) |
| `tool-names` | Set | [] | Tool names that require approval. Empty = use custom `ToolApprovalPolicy` |

### ToolPolicyProperties

Enforces write/read tool access by channel.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable tool policy enforcement (opt-in) |
| `write-tool-names` | Set | [] | Tool names considered "write" (side-effecting) |
| `deny-write-channels` | Set | `[slack]` | Channels where write tools are denied (fail-closed) |
| `allow-write-tool-names-in-deny-channels` | Set | [] | Write tools allowed even in deny channels |
| `allow-write-tool-names-by-channel` | Map | {} | Channel-scoped allowlist for deny channels |
| `deny-write-message` | String | `Error: This tool is not allowed in this channel` | Error message returned when a tool call is denied |
| `dynamic.enabled` | Boolean | false | Enable DB-backed dynamic tool policy (admin API updates + periodic refresh) |
| `dynamic.refresh-ms` | Long | 10000 | Cache refresh interval for dynamic policy (ms) |

### AuthProperties

Nested under `arc.reactor.auth`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `jwt-secret` | String | `""` | JWT signing secret. Must be set via `ARC_REACTOR_AUTH_JWT_SECRET` env var |
| `default-tenant-id` | String | `default` | Default tenant ID when not specified in the request |
| `token-revocation-store` | String | `memory` | Token revocation store backend: `memory`, `jdbc`, or `redis` |
| `token-revocation-redis-key-prefix` | String | `arc:auth:revoked` | Redis key prefix for revoked tokens (only when `token-revocation-store=redis`) |
| `public-actuator-health` | Boolean | true | Allow unauthenticated access to `/actuator/health`. The `prod` profile overrides this to `false` |

### ErrorReportProperties

Nested under `arc.reactor.error-report`. Runs a dedicated lightweight agent to analyze errors.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable error report agent (opt-in) |
| `max-concurrent-requests` | Int | 5 | Maximum concurrent error report requests |
| `request-timeout-ms` | Long | 10000 | Timeout for the error report agent (ms) |
| `max-tool-calls` | Int | 3 | Maximum tool calls per error report execution |

### OutputGuardProperties

Post-execution response validation for PII, policy violations, and custom regex patterns.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Enable output guard |
| `pii-masking-enabled` | Boolean | true | Enable built-in PII masking stage |
| `dynamic-rules-enabled` | Boolean | true | Enable dynamic runtime-managed regex rules (admin-managed) |
| `dynamic-rules-refresh-ms` | Long | 3000 | Refresh interval for dynamic rules cache (ms) |
| `custom-patterns` | List | [] | Custom regex patterns for blocking or masking. Each entry has `name`, `pattern`, and `action` (`REJECT` or `MASK`) |

### SchedulerProperties

Dynamic cron-scheduled MCP tool execution managed via REST API.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable dynamic scheduler (opt-in) |
| `thread-pool-size` | Int | 5 | Thread pool size for scheduled task execution |
| `default-timezone` | String | system default | Default timezone for scheduled jobs when not specified |
| `default-execution-timeout-ms` | Long | 300000 | Default execution timeout for jobs (ms) |
| `max-executions-per-job` | Int | 100 | Maximum execution history entries to retain per job. `0` means unlimited |

### IntentProperties

Rule-based + optional LLM intent classification.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable intent classification (opt-in) |
| `confidence-threshold` | Double | 0.6 | Minimum confidence to apply an intent profile |
| `llm-model` | String | null | LLM provider for classification. `null` uses default provider |
| `rule-confidence-threshold` | Double | 0.8 | Minimum rule-based confidence to skip LLM fallback |
| `max-examples-per-intent` | Int | 3 | Maximum few-shot examples per intent in LLM prompt |
| `max-conversation-turns` | Int | 2 | Maximum conversation turns for context-aware classification |
| `blocked-intents` | Set | [] | Intent names to block -- requests classified as these intents are rejected |

### WebhookConfigProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable webhook notifications (opt-in) |
| `url` | String | `""` | POST target URL |
| `timeout-ms` | Long | 5000 | HTTP timeout (ms) |
| `include-conversation` | Boolean | false | Whether to include full conversation in payload |

### MultimodalProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Enable multimodal support (file uploads and media URLs) |
| `max-file-size-bytes` | Long | 10485760 | Maximum allowed size per uploaded file (10 MB) |
| `max-files-per-request` | Int | 5 | Maximum number of files allowed per multipart request |

### TracingProperties

Nested under `arc.reactor.tracing`. When OpenTelemetry is not on the classpath, a no-op tracer is used (zero overhead).

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Enable span emission |
| `service-name` | String | `arc-reactor` | Service name attached to spans as `service.name` |
| `include-user-id` | Boolean | false | Include user ID as a span attribute. Disabled by default to prevent PII leakage |

### CitationProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable citation auto-formatting (opt-in) |
| `format` | String | `markdown` | Citation format. Currently only `markdown` is supported |

### ToolResultCacheProperties

Caches identical tool invocations (same tool name + same arguments) within the same ReAct loop.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Enable tool result caching |
| `ttl-seconds` | Long | 60 | Time-to-live for cached entries (seconds) |
| `max-size` | Long | 200 | Maximum number of cached entries |

### ToolEnrichmentProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `requester-aware-tool-names` | Set | [] | Tool names that receive the caller's identity from request metadata when the LLM omits it |

### MemoryProperties

Nested under `arc.reactor.memory`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `summary.enabled` | Boolean | false | Enable hierarchical memory summarization (opt-in) |
| `summary.trigger-message-count` | Int | 20 | Minimum message count before summarization triggers |
| `summary.recent-message-count` | Int | 10 | Number of recent messages to keep verbatim (not summarized) |
| `summary.llm-model` | String | null | LLM provider for summarization. `null` uses default provider |
| `summary.max-narrative-tokens` | Int | 500 | Maximum token budget for the narrative summary |
| `user.enabled` | Boolean | false | Enable per-user long-term memory (opt-in) |
| `user.inject-into-prompt` | Boolean | false | Inject user memory into the system prompt |
| `user.max-prompt-injection-chars` | Int | 1000 | Maximum character length for the injected user memory context block |
| `user.max-recent-topics` | Int | 10 | Maximum number of recent topics to retain per user |

### ResponseProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `max-length` | Int | 0 | Maximum response length in characters. `0` means unlimited |
| `filters-enabled` | Boolean | true | Enable response filter chain processing |

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

#### SemanticCacheProperties

Nested under `arc.reactor.cache.semantic`. Requires Redis + embedding dependencies.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable semantic response cache (opt-in) |
| `similarity-threshold` | Double | 0.92 | Minimum cosine similarity for a semantic cache hit |
| `max-candidates` | Int | 50 | Maximum recent semantic candidates to evaluate per lookup |
| `max-entries-per-scope` | Long | 1000 | Maximum semantic cache entries per scope fingerprint |
| `key-prefix` | String | `arc:cache` | Redis key prefix for semantic cache records and indexes |

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

### CorsProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable CORS (opt-in) |
| `allowed-origins` | List | `["http://localhost:3000"]` | Allowed origins |
| `allowed-methods` | List | `["GET","POST","PUT","DELETE","OPTIONS"]` | Allowed HTTP methods |
| `allowed-headers` | List | `["*"]` | Allowed headers |
| `allow-credentials` | Boolean | false | Allow credentials (cookies, Authorization header) |
| `max-age` | Long | 3600 | Preflight cache duration in seconds |

### SecurityHeadersProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Enable security headers |

### McpConfigProperties

MCP servers are registered and managed via REST API (`/api/mcp/servers`).

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `connection-timeout-ms` | Long | 30000 | MCP connection timeout (ms) |
| `allow-private-addresses` | Boolean | false | Allow connections to private/reserved IP addresses. Enable only for local development |
| `security.allowed-server-names` | Set | [] | Allowed MCP server names. Empty = allow all |
| `security.max-tool-output-length` | Int | 50000 | Maximum tool output length in characters |
| `security.allowed-stdio-commands` | Set | `[npx, node, python, python3, uvx, uv, docker, deno, bun]` | Allowed STDIO command executables |
| `reconnection.enabled` | Boolean | true | Enable auto-reconnection for failed MCP servers |
| `reconnection.max-attempts` | Int | 5 | Maximum reconnection attempts |
| `reconnection.initial-delay-ms` | Long | 5000 | Initial delay between reconnection attempts (ms) |
| `reconnection.multiplier` | Double | 2.0 | Backoff multiplier for subsequent attempts |
| `reconnection.max-delay-ms` | Long | 60000 | Maximum delay between reconnection attempts (ms) |

### PromptLabProperties

Nested under `arc.reactor.prompt-lab`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | false | Enable Prompt Lab (opt-in) |
| `max-concurrent-experiments` | Int | 3 | Maximum concurrent experiments |
| `max-queries-per-experiment` | Int | 100 | Maximum test queries per experiment |
| `max-versions-per-experiment` | Int | 10 | Maximum prompt versions per experiment |
| `max-repetitions` | Int | 5 | Maximum repetitions per version-query pair |
| `default-judge-model` | String | null | Default LLM judge model. `null` uses same as experiment model |
| `default-judge-budget-tokens` | Int | 100000 | Default token budget for LLM judge evaluations |
| `experiment-timeout-ms` | Long | 600000 | Experiment execution timeout (ms) |
| `candidate-count` | Int | 3 | Number of candidate prompts to auto-generate |
| `min-negative-feedback` | Int | 5 | Minimum negative feedback count to trigger auto pipeline |
| `schedule.enabled` | Boolean | false | Enable scheduled auto-optimization |
| `schedule.cron` | String | `0 0 2 * * *` | Cron expression (default: daily at 2 AM) |
| `schedule.template-ids` | List | [] | Target template IDs. Empty = all templates |

### ApiVersionContract

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Enables API version contract validation for `X-Arc-Api-Version` |
| `current` | String | `v1` | Current API version returned via response header |
| `supported` | String | `v1` | Comma-separated supported versions (e.g., `v1,v2`) |

**Behavior:**
- Request header `X-Arc-Api-Version` is optional
- If missing, server uses `current`
- If provided and unsupported, request is rejected with `400 Bad Request`
- Responses include `X-Arc-Api-Version` and `X-Arc-Api-Supported-Versions`

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

## Production Profile Overrides (`application-prod.yml`)

When `SPRING_PROFILES_ACTIVE=prod` is set, the following overrides are applied:

```yaml
spring:
  codec:
    max-in-memory-size: 1MB                        # Restrict in-memory buffer size

arc:
  reactor:
    concurrency:
      max-concurrent-requests: 50                  # Increased from default 20
    security-headers:
      enabled: true
    auth:
      public-actuator-health: false                # Disable public health endpoint access
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
      prompt-caching:
        enabled: true            # Save on Anthropic prompt tokens
    rag:
      max-context-tokens: 2000   # Save RAG tokens
      top-k: 3                   # Fewer search results
```

### Security Hardening

```yaml
arc:
  reactor:
    guard:
      enabled: true
      rate-limit-per-minute: 5
      rate-limit-per-hour: 50
      injection-detection-enabled: true
      canary-token-enabled: true
      topic-drift-enabled: true
    boundaries:
      input-max-chars: 3000
      system-prompt-max-chars: 20000
    concurrency:
      request-timeout-ms: 15000  # Short timeout
    output-guard:
      enabled: true
      pii-masking-enabled: true
```
