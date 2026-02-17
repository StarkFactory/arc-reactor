# Observability & Metrics

> **Key file:** `agent/metrics/AgentMetrics.kt`
> This document covers the agent metrics interface, available metric points, and how to integrate with monitoring backends.

## Overview

Arc Reactor provides a framework-agnostic `AgentMetrics` interface for recording agent execution metrics. By default, a `NoOpAgentMetrics` implementation is used (metrics disabled). Users can provide a custom implementation backed by Micrometer, Prometheus, Datadog, or any other metrics backend.

## AgentMetrics Interface

The interface provides 9 metric recording methods across 5 categories:

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

### Resilience Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordCacheHit(cacheKey)` | When a cached response is returned | SHA-256 cache key |
| `recordCacheMiss(cacheKey)` | When no cached response is found | SHA-256 cache key |
| `recordCircuitBreakerStateChange(name, from, to)` | When the circuit breaker transitions state | CB name, previous state, new state |
| `recordFallbackAttempt(model, success)` | When a fallback model is attempted | Model name, success boolean |

### Token Usage Metrics

| Method | When Called | Parameters |
|--------|-----------|------------|
| `recordTokenUsage(usage)` | After each LLM call | `TokenUsage(promptTokens, completionTokens, totalTokens)` |

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

The `NoOpAgentMetrics` is registered with `@ConditionalOnMissingBean`. Simply define your own `AgentMetrics` bean to override:

```kotlin
@Configuration
class MetricsConfig {
    @Bean
    fun agentMetrics(registry: MeterRegistry): AgentMetrics =
        MicrometerAgentMetrics(registry)
}
```

## Backward Compatibility

All methods added in 2.6.0+ have **default empty implementations** in the interface. Existing custom `AgentMetrics` implementations will continue to work without changes. Only the original 3 methods (`recordExecution`, `recordToolCall`, `recordGuardRejection`) are abstract.

## Metric Points in the Pipeline

```
Request
  |
  v
Guard Pipeline ----[rejection]----> recordGuardRejection()
  |
  v
Cache Check ----[hit]----> recordCacheHit() --> return cached response
  |  [miss] --> recordCacheMiss()
  v
LLM Call -----> recordTokenUsage()
  |
  +--> [tool calls] --> recordToolCall() (per tool)
  |
  +--> [circuit breaker state change] --> recordCircuitBreakerStateChange()
  |
  +--> [fallback triggered] --> recordFallbackAttempt() (per model)
  |
  v
Response
  |
  +--> recordExecution() (non-streaming)
  +--> recordStreamingExecution() (streaming)
```
