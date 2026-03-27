# Observability & Metrics

> **Key file:** `agent/metrics/AgentMetrics.kt`
> This document covers the agent metrics interface, available metric points, and how to integrate with monitoring backends.

## Overview

Arc Reactor provides a framework-agnostic `AgentMetrics` interface for recording agent execution metrics. The auto-configuration selects the implementation based on the classpath:

- **`MicrometerAgentMetrics`** is auto-registered (with `@Primary`) when a `MeterRegistry` bean is available on the classpath (i.e., when Micrometer is included as a dependency). This is the production-recommended path.
- **`NoOpAgentMetrics`** is auto-registered via `@ConditionalOnMissingBean` as the fallback when no `MeterRegistry` is present. All methods are no-ops, so metrics are effectively disabled.

Users can always override both by defining a custom `AgentMetrics` bean.

## AgentMetrics Interface

The interface provides metric recording methods across the following categories:

### Execution Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordExecution(result)` | After each non-streaming agent execution completes | `AgentResult` (success, durationMs, content, errorCode) |
| `recordStreamingExecution(result)` | After each streaming agent execution completes | `AgentResult` (same as above) |

### Tool Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordToolCall(toolName, durationMs, success)` | After each tool call (local or MCP) | Tool name, duration in ms, success boolean |

### Guard Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordGuardRejection(stage, reason)` | When the Guard pipeline rejects a request | Stage name (e.g., "InjectionDetection"), rejection reason |
| `recordGuardRejection(stage, reason, metadata)` | Same, with request metadata | Stage, reason, metadata map |

### Resilience Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordCacheHit(cacheKey)` | When a cached response is returned (base method) | SHA-256 cache key |
| `recordExactCacheHit(cacheKey)` | When a byte-identical request key matches a cached response | SHA-256 cache key |
| `recordSemanticCacheHit(cacheKey)` | When a semantically similar prompt matches a cached response | SHA-256 cache key |
| `recordCacheMiss(cacheKey)` | When no cached response is found | SHA-256 cache key |
| `recordCircuitBreakerStateChange(name, from, to)` | When the circuit breaker transitions state | CB name, previous state, new state |
| `recordFallbackAttempt(model, success)` | When a fallback model is attempted | Model name, success boolean |

`recordExactCacheHit` and `recordSemanticCacheHit` delegate to `recordCacheHit` by default, so existing implementations that only override `recordCacheHit` continue to work.

### Token Usage Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordTokenUsage(usage)` | After each LLM call | `TokenUsage(promptTokens, completionTokens, totalTokens)` |
| `recordTokenUsage(usage, metadata)` | Same, with request metadata | TokenUsage, metadata map |

### Output Guard Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordOutputGuardAction(stage, action, reason)` | When output guard processes response | Stage, action ("allowed"/"modified"/"rejected"), reason |
| `recordOutputGuardAction(stage, action, reason, metadata)` | Same, with request metadata | Stage, action, reason, metadata map |

### Boundary Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordBoundaryViolation(violation, policy, limit, actual)` | When output boundary policy triggers | Violation type (e.g., "output_too_long"), policy action (e.g., "truncate"), limit, actual |
| `recordBoundaryViolation(violation, policy, limit, actual, metadata)` | Same, with request metadata | Violation, policy, limit, actual, metadata map |

### Response Observation Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordUnverifiedResponse(metadata)` | When a response could not be verified from approved sources | Metadata map (channel, query info) |
| `recordResponseObservation(metadata)` | After each final response for product-value insights | Metadata map (answerMode, grounded, deliveryMode, channel, toolFamily) |

### Latency Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordStageLatency(stage, durationMs, metadata)` | After each pipeline stage completes | Stage name, duration in ms, metadata map |
| `recordLlmLatency(model, durationMs)` | After each LLM call for SLA tracking (P50/P95/P99) | Model name, duration in ms |

### Tool Result Cache Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordToolResultCacheHit(toolName, cacheKey)` | When a cached tool result is reused (same tool + same args) | Tool name, cache key |
| `recordToolResultCacheMiss(toolName, cacheKey)` | When a tool is executed and the result stored in cache | Tool name, cache key |

### Tool Output Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordToolOutputSize(toolName, sizeBytes, truncated)` | After tool execution for output size monitoring | Tool name, output size in bytes, whether truncated |

### Active Request Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordActiveRequests(count)` | When the number of in-flight agent requests changes | Current active count |

### RAG Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordRagRetrieval(status, durationMs)` | After a RAG retrieval attempt | Status ("success", "empty", "timeout", "error"), duration in ms |

## Implementation

### Custom Metrics with Micrometer

```kotlin
@Bean
fun agentMetrics(registry: MeterRegistry): AgentMetrics = MicrometerAgentMetrics(registry)

class MicrometerAgentMetrics(private val registry: MeterRegistry) : AgentMetrics {

    private val executionCounter = registry.counter("arc.agent.executions")
    private val executionTimer = registry.timer("arc.agent.execution.duration")
    private val errorCounter = registry.counter("arc.agent.errors")
    private val toolCounter = registry.counter("arc.agent.tool.calls")
    private val guardRejectionCounter = registry.counter("arc.agent.guard.rejections")
    private val cacheHitCounter = registry.counter("arc.agent.cache.hits")
    private val cacheMissCounter = registry.counter("arc.agent.cache.misses")
    private val fallbackCounter = registry.counter("arc.agent.fallback.attempts")
    private val tokenTotal = AtomicLong(0)

    init {
        registry.gauge("arc.agent.tokens.total", tokenTotal)
    }

    override fun recordExecution(result: AgentResult) {
        executionCounter.increment()
        executionTimer.record(result.durationMs, TimeUnit.MILLISECONDS)
        if (!result.success) errorCounter.increment()
    }

    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {
        registry.counter("arc.agent.tool.calls", "tool", toolName).increment()
        registry.timer("arc.agent.tool.duration", "tool", toolName)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }

    override fun recordGuardRejection(stage: String, reason: String) {
        registry.counter("arc.agent.guard.rejections", "stage", stage).increment()
    }

    override fun recordCacheHit(cacheKey: String) { cacheHitCounter.increment() }
    override fun recordCacheMiss(cacheKey: String) { cacheMissCounter.increment() }

    override fun recordCircuitBreakerStateChange(
        name: String, from: CircuitBreakerState, to: CircuitBreakerState
    ) {
        registry.counter("arc.agent.cb.transitions", "from", from.name, "to", to.name).increment()
    }

    override fun recordFallbackAttempt(model: String, success: Boolean) {
        registry.counter("arc.agent.fallback", "model", model, "success", success.toString()).increment()
    }

    override fun recordTokenUsage(usage: TokenUsage) {
        tokenTotal.addAndGet(usage.totalTokens.toLong())
        registry.counter("arc.agent.tokens.prompt").increment(usage.promptTokens.toDouble())
        registry.counter("arc.agent.tokens.completion").increment(usage.completionTokens.toDouble())
    }

    override fun recordStreamingExecution(result: AgentResult) {
        registry.counter("arc.agent.streaming.executions").increment()
        if (!result.success) errorCounter.increment()
    }
}
```

### Simple Logging Metrics

```kotlin
@Component
class LoggingAgentMetrics : AgentMetrics {
    private val logger = KotlinLogging.logger {}

    override fun recordExecution(result: AgentResult) {
        logger.info { "Execution: success=${result.success}, duration=${result.durationMs}ms" }
    }

    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {
        logger.info { "ToolCall: tool=$toolName, duration=${durationMs}ms, success=$success" }
    }

    override fun recordGuardRejection(stage: String, reason: String) {
        logger.warn { "GuardRejection: stage=$stage, reason=$reason" }
    }

    override fun recordCacheHit(cacheKey: String) {
        logger.info { "CacheHit: key=${cacheKey.take(16)}..." }
    }

    override fun recordCacheMiss(cacheKey: String) {
        logger.info { "CacheMiss: key=${cacheKey.take(16)}..." }
    }

    override fun recordCircuitBreakerStateChange(
        name: String, from: CircuitBreakerState, to: CircuitBreakerState
    ) {
        logger.warn { "CircuitBreaker: name=$name, $from -> $to" }
    }

    override fun recordFallbackAttempt(model: String, success: Boolean) {
        logger.info { "FallbackAttempt: model=$model, success=$success" }
    }

    override fun recordTokenUsage(usage: TokenUsage) {
        logger.info { "TokenUsage: prompt=${usage.promptTokens}, completion=${usage.completionTokens}, total=${usage.totalTokens}" }
    }

    override fun recordStreamingExecution(result: AgentResult) {
        logger.info { "StreamingExecution: success=${result.success}, duration=${result.durationMs}ms" }
    }
}
```

## Bean Registration

The auto-configuration registers metrics beans in the following order:

1. **`MicrometerAgentMetrics`** -- registered with `@Primary` and `@ConditionalOnBean(MeterRegistry::class)`. If Micrometer is on the classpath and a `MeterRegistry` bean exists, this implementation is used automatically.
2. **`NoOpAgentMetrics`** -- registered with `@ConditionalOnMissingBean`. Used as the fallback when no `MeterRegistry` is available.

To provide a fully custom implementation, define your own `AgentMetrics` bean:

```kotlin
@Configuration
class MetricsConfig {
    @Bean
    fun agentMetrics(registry: MeterRegistry): AgentMetrics =
        MyCustomAgentMetrics(registry)
}
```

## Backward Compatibility

All methods added in 2.6.0+ have **default empty implementations** in the interface. Existing custom `AgentMetrics` implementations will continue to work without changes. Only the original 3 methods (`recordExecution`, `recordToolCall`, `recordGuardRejection`) are abstract.

The metadata overloads (added in 2.7.0) delegate to the non-metadata variants by default, so existing implementations do not need to override them.

## Built-in Micrometer Metric Names

When `MicrometerAgentMetrics` is active, the following metric names are registered:

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `arc.agent.executions` | Counter | `success`, `error_code` | Agent execution count |
| `arc.agent.execution.duration` | Timer | `success` | Agent execution duration (P50/P95/P99) |
| `arc.agent.errors` | Counter | `error_code` | Agent error count |
| `arc.agent.tool.calls` | Counter | `tool`, `success` | Tool call count |
| `arc.agent.tool.duration` | Timer | `tool`, `success` | Tool call duration |
| `arc.agent.guard.rejections` | Counter | `stage` | Guard rejection count |
| `arc.agent.cache.hits` | Counter | `mode` (exact/semantic) | Response cache hits |
| `arc.agent.cache.misses` | Counter | -- | Response cache misses |
| `arc.agent.circuitbreaker.transitions` | Counter | `name`, `from`, `to` | Circuit breaker state transitions |
| `arc.agent.fallback.attempts` | Counter | `model`, `success` | Model fallback attempts |
| `arc.agent.tokens.total` | DistributionSummary | -- | Token usage per LLM call |
| `arc.agent.streaming.executions` | Counter | `success` | Streaming execution count |
| `arc.agent.output.guard.actions` | Counter | `stage`, `action` | Output guard actions |
| `arc.agent.boundary.violations` | Counter | `violation`, `policy` | Boundary policy violations |
| `arc.agent.responses.unverified` | Counter | `channel` | Unverified response count |
| `arc.agent.stage.duration` | Timer | `stage`, `channel` | Per-stage latency |
| `arc.agent.llm.latency` | Timer | `model` | LLM call latency (P50/P95/P99) |
| `arc.agent.tool.output.size` | DistributionSummary | `tool` | Tool output size in bytes |
| `arc.agent.tool.output.truncated` | Counter | `tool` | Truncated tool output count |
| `arc.agent.tool.result.cache.hits` | Counter | `tool` | Tool result cache hits |
| `arc.agent.tool.result.cache.misses` | Counter | `tool` | Tool result cache misses |
| `arc.agent.rag.retrievals` | Counter | `status` | RAG retrieval count |
| `arc.agent.rag.retrieval.duration` | Timer | `status` | RAG retrieval duration |
| `arc.agent.active_requests` | Gauge | -- | Current in-flight agent requests |
| `arc.agent.request.cost` | DistributionSummary | `model`, `tenantId` | Per-request cost in USD |
| `arc.agent.cost.total.usd` | Counter | `model`, `tenantId` | Cumulative total cost |

### SLA Metrics

When `MicrometerSlaMetrics` is active (requires `MeterRegistry` on classpath), the following SLA/SLO metrics are registered:

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `arc.sla.react.convergence` | Timer | `stop_reason`, `step_bucket` | ReAct loop convergence duration (P50/P95/P99) |
| `arc.sla.react.convergence.total` | Counter | `stop_reason`, `step_bucket` | ReAct loop convergence count by stop reason and step bucket |
| `arc.sla.tool.failure` | Counter | `tool`, `error_type` | Tool failure count by error type (timeout, connection, validation, internal, mcp_unavailable) |
| `arc.sla.tool.failure.duration` | Timer | `tool`, `error_type` | Failed tool call duration |
| `arc.sla.availability` | Gauge | -- | Rolling-window system availability ratio (0.0--1.0, last 100 samples) |
| `arc.sla.e2e.latency` | Timer | `channel` | End-to-end request latency (P50/P95/P99) per channel (rest, slack, etc.) |

> **Key file:** `agent/metrics/SlaMetrics.kt` (interface), `agent/metrics/MicrometerSlaMetrics.kt` (implementation)

### Cache Metrics

When `MicrometerCacheMetricsRecorder` is active (requires `MeterRegistry` on classpath), the following response-cache metrics are registered:

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `arc.cache.hits` | Counter | `type` (exact/semantic) | Response cache hits by type |
| `arc.cache.misses` | Counter | -- | Response cache misses |
| `arc.cache.semantic.similarity` | DistributionSummary | -- | Semantic cache hit similarity score distribution (P50/P95/P99) |
| `arc.cache.cost.saved.estimate` | Counter | `model` | Estimated cost saved by cache hits (USD) |

> **Key file:** `cache/CacheMetricsRecorder.kt` (interface + `MicrometerCacheMetricsRecorder` implementation)

### RAG Chunking Metrics

When `InstrumentedDocumentChunker` is active (requires `MeterRegistry` on classpath and RAG chunking enabled), the following chunking metrics are registered:

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `arc.rag.documents.chunked` | Counter | -- | Number of documents that were actually split into chunks |
| `arc.rag.chunks.created` | Counter | -- | Total number of chunks created |
| `arc.rag.chunk.size.chars` | DistributionSummary | -- | Chunk size distribution in characters (P50/P95) |

> **Key file:** `rag/chunking/InstrumentedDocumentChunker.kt`

## arc-admin Metric Pipeline

The `arc-admin` module provides a production-grade metric collection pipeline that replaces `NoOpAgentMetrics` with tenant-scoped observability.

### Components

| Component | Role |
|-----------|------|
| `MetricCollectorAgentMetrics` | `@Primary` `AgentMetrics` implementation. Publishes guard/token events to the ring buffer. Calculates `estimatedCostUsd` via `CostCalculator`. |
| `MetricCollectionHook` | `AfterAgentCompleteHook` + `AfterToolCallHook` (order=200). Captures enriched execution/tool events with latency breakdown, sessionId, userId. |
| `HitlEventHook` | `AfterToolCallHook` (order=201). Captures HITL approval/rejection events from `ToolCallOrchestrator` metadata. |
| `QuotaEnforcerHook` | `BeforeAgentStartHook` (order=5). Enforces tenant quotas and publishes `QuotaEvent` on rejection/warning. |
| `MetricRingBuffer` | Lock-free ring buffer (Disruptor-inspired, default 8192 slots). Producers never block; full buffer → drop + counter. |
| `MetricWriter` | Scheduled drain of the ring buffer → batched JDBC flush via `MetricEventStore`. Safety net: re-calculates cost for `TokenUsageEvent` with zero cost. |

### Data Flow

```
Agent thread                           Background writer
    |                                       |
    +-- QuotaEnforcerHook (order=5) ---|    |
    +-- MetricCollectorAgentMetrics ---|    |
    +-- MetricCollectionHook (200) ----|    |
    +-- HitlEventHook (201) ----------|    |
                                      v    |
                              MetricRingBuffer
                                      |    |
                                      +----+
                                      v
                              MetricWriter (scheduled)
                                      |
                                      v
                              MetricEventStore (JDBC)
```

### Tenant Propagation

Tenant ID is resolved from `metadata["tenantId"]` passed through the metadata overloads, **not** from ThreadLocal. This ensures correct tenant attribution in async/coroutine contexts.

#### Multi-Agent Metadata Propagation

In multi-agent orchestration, tenant metadata must flow from the parent command to sub-agents:

| Orchestrator | Propagation Method | Status |
|-------------|-------------------|--------|
| `SupervisorOrchestrator` | `command.copy()` → supervisor agent | Propagated |
| `WorkerAgentTool` | `parentCommand` parameter → worker `AgentCommand` | Propagated |
| `SequentialOrchestrator` | `command.copy()` → each node | Propagated |
| `ParallelOrchestrator` | `command.copy()` → each concurrent node | Propagated |

`WorkerAgentTool` receives the parent `AgentCommand` at construction time (via `SupervisorOrchestrator`) and copies `metadata` and `userId` into each worker's command. This ensures `tenantId`, `sessionId`, and `channel` are available to all hooks in the sub-agent's execution pipeline.

### Event Types

All events extend `sealed class MetricEvent` and are persisted to TimescaleDB hypertables.

| Event | Table | Source | Description |
|-------|-------|--------|-------------|
| `AgentExecutionEvent` | `metric_agent_executions` | `MetricCollectionHook` | Run-level metrics: success, latency breakdown, guard/tool/LLM duration |
| `ToolCallEvent` | `metric_tool_calls` | `MetricCollectionHook` | Per-tool call: name, source (local/MCP), duration, error |
| `TokenUsageEvent` | `metric_token_usage` | `MetricCollectorAgentMetrics` | Per-LLM call: model, provider, tokens, `estimatedCostUsd` |
| `SessionEvent` | `metric_sessions` | `MetricCollectionHook` | Session aggregate: turn count, total duration/tokens/cost |
| `GuardEvent` | `metric_guard_events` | `MetricCollectorAgentMetrics` | Guard rejection: stage, category, reason, output guard flag |
| `McpHealthEvent` | `metric_mcp_health` | `MetricCollectionHook` | MCP server health: response time, status, error tracking |
| `EvalResultEvent` | `metric_eval_results` | External ingestion | Eval run: pass/fail, score, latency, assertion type |
| `QuotaEvent` | `metric_quota_events` | `QuotaEnforcerHook` | Quota rejection/warning: action, usage, limit, percent |
| `HitlEvent` | `metric_hitl_events` | `HitlEventHook` | HITL decision: tool, approved, wait time, rejection reason |

### Token Cost Calculation

`MetricCollectorAgentMetrics` computes `estimatedCostUsd` on the hot path using `CostCalculator`:

```kotlin
val cost = costCalculator.calculate(
    provider = provider, model = model, time = Instant.now(),
    promptTokens = usage.promptTokens, completionTokens = usage.completionTokens
)
```

- Known model pricing → non-zero cost in the event
- Unknown model or exception → `BigDecimal.ZERO` (safety fallback)
- `MetricWriter.enrichCosts()` retries only when cost is ZERO (off-hot-path safety net)

### Quota Events

`QuotaEnforcerHook` publishes `QuotaEvent` at 3 rejection points and 1 warning threshold:

| Action | Trigger | Continues? |
|--------|---------|------------|
| `rejected_requests` | `usage.requests >= quota.maxRequestsPerMonth` | No (request rejected) |
| `rejected_tokens` | `usage.tokens >= quota.maxTokensPerMonth` | No (request rejected) |
| `rejected_suspended` | `tenant.status != ACTIVE` | No (request rejected) |
| `warning` | `usage.requests >= 90%` of quota | Yes (request continues) |

The 90% warning is **deduplicated per tenant per month** via `ConcurrentHashMap.newKeySet()` to prevent alert noise.

### HITL Events

`HitlEventHook` reads metadata set by `ToolCallOrchestrator` during human-in-the-loop approval:

| Metadata Key | Type | Description |
|--------------|------|-------------|
| `hitlWaitMs_{toolName}_{callIndex}` | Long | How long the tool waited for human approval (ms) |
| `hitlApproved_{toolName}_{callIndex}` | Boolean | Whether the human approved the tool call |
| `hitlRejectionReason_{toolName}_{callIndex}` | String? | Reason for rejection (if rejected) |

If `hitlWaitMs_{toolName}_{callIndex}` is absent, the hook falls back to legacy keys
(`hitlWaitMs_{toolName}`, etc.). If both are absent, the hook skips silently.

### Database Schema

Tables are defined in Flyway migration `V8__create_quota_and_hitl_tables.sql`:

- `metric_quota_events` — TimescaleDB hypertable, 7-day chunk, 7-day compression, 90-day retention
- `metric_hitl_events` — TimescaleDB hypertable, 7-day chunk, 7-day compression, 90-day retention

## Metric Points in the Pipeline

```
Request
  |
  +--> recordActiveRequests(+1)
  v
QuotaEnforcerHook ----[rejected]----> QuotaEvent
  |                 --[90% usage]---> QuotaEvent (warning, deduped)
  v
Guard Pipeline ----[rejection]----> recordGuardRejection()
  |
  +--> recordStageLatency("guard", ...)
  v
Cache Check ----[hit]----> recordExactCacheHit() or recordSemanticCacheHit() --> return cached
  |  [miss] --> recordCacheMiss()
  v
RAG Retrieval -----> recordRagRetrieval(status, durationMs)
  |
  v
LLM Call -----> recordTokenUsage()
  |         --> recordLlmLatency(model, durationMs)
  |
  +--> [tool calls] --> recordToolCall() (per tool)
  |                 --> recordToolOutputSize() (per tool)
  |                 --> recordToolResultCacheHit() or recordToolResultCacheMiss()
  |                 --> HitlEventHook --> HitlEvent (if HITL metadata present)
  |
  +--> [circuit breaker state change] --> recordCircuitBreakerStateChange()
  |
  +--> [fallback triggered] --> recordFallbackAttempt() (per model)
  |
  v
Output Guard ----[action]----> recordOutputGuardAction()
  |
  +--> [boundary violation] --> recordBoundaryViolation()
  |
  v
Response
  |
  +--> recordExecution() (non-streaming)
  +--> recordStreamingExecution() (streaming)
  +--> recordResponseObservation() (product-value insights)
  +--> recordUnverifiedResponse() (if not verified)
  +--> recordActiveRequests(-1)
```
