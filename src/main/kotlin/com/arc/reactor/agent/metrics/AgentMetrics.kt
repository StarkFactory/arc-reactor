package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.resilience.CircuitBreakerState

/**
 * Agent metrics interface for observability.
 *
 * Provides a framework-agnostic abstraction for recording agent execution metrics.
 * Users can implement this with Micrometer, Prometheus, or any metrics backend.
 *
 * All new methods (added in 2.7.0) have default empty implementations to preserve
 * backward compatibility with existing user implementations.
 *
 * ## Micrometer Example
 * ```kotlin
 * class MicrometerAgentMetrics(private val registry: MeterRegistry) : AgentMetrics {
 *     private val executionCounter = registry.counter("arc.agent.executions")
 *     private val executionTimer = registry.timer("arc.agent.execution.duration")
 *     private val errorCounter = registry.counter("arc.agent.errors")
 *     private val toolCounter = registry.counter("arc.agent.tool.calls")
 *     private val cacheHitCounter = registry.counter("arc.agent.cache.hits")
 *     private val cacheMissCounter = registry.counter("arc.agent.cache.misses")
 *     private val tokenGauge = registry.gauge("arc.agent.tokens.total", AtomicLong(0))
 *
 *     override fun recordExecution(result: AgentResult) {
 *         executionCounter.increment()
 *         executionTimer.record(result.durationMs, java.util.concurrent.TimeUnit.MILLISECONDS)
 *         if (!result.success) errorCounter.increment()
 *     }
 *
 *     override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {
 *         toolCounter.increment()
 *         if (!success) errorCounter.increment()
 *     }
 *
 *     override fun recordCacheHit(cacheKey: String) { cacheHitCounter.increment() }
 *     override fun recordCacheMiss(cacheKey: String) { cacheMissCounter.increment() }
 *     override fun recordTokenUsage(usage: TokenUsage) { tokenGauge?.set(usage.totalTokens.toLong()) }
 * }
 * ```
 *
 * @see NoOpAgentMetrics for the default no-op implementation
 */
interface AgentMetrics {
    /**
     * Record an agent execution result.
     *
     * @param result The agent execution result
     */
    fun recordExecution(result: AgentResult)

    /**
     * Record a tool call.
     *
     * @param toolName Name of the tool called
     * @param durationMs Duration of the tool call in milliseconds
     * @param success Whether the tool call succeeded
     */
    fun recordToolCall(toolName: String, durationMs: Long, success: Boolean)

    /**
     * Record a guard rejection.
     *
     * @param stage The guard stage that rejected the request
     * @param reason The rejection reason
     */
    fun recordGuardRejection(stage: String, reason: String)

    /**
     * Record a response cache hit.
     *
     * @param cacheKey The cache key that was hit
     */
    fun recordCacheHit(cacheKey: String) {}

    /**
     * Record a response cache miss.
     *
     * @param cacheKey The cache key that was missed
     */
    fun recordCacheMiss(cacheKey: String) {}

    /**
     * Record a circuit breaker state transition.
     *
     * @param name The circuit breaker name
     * @param from Previous state
     * @param to New state
     */
    fun recordCircuitBreakerStateChange(name: String, from: CircuitBreakerState, to: CircuitBreakerState) {}

    /**
     * Record a model fallback attempt.
     *
     * @param model The model that was attempted
     * @param success Whether the attempt succeeded
     */
    fun recordFallbackAttempt(model: String, success: Boolean) {}

    /**
     * Record LLM token usage from a single request.
     *
     * Called after each LLM call with the token counts from the response metadata.
     *
     * @param usage The token usage from the LLM response
     */
    fun recordTokenUsage(usage: TokenUsage) {}

    /**
     * Record a streaming execution result.
     *
     * Separate from [recordExecution] to allow distinguishing streaming vs non-streaming metrics.
     *
     * @param result The streaming execution result
     */
    fun recordStreamingExecution(result: AgentResult) {}
}

/**
 * No-op metrics implementation (default).
 *
 * Used when no metrics backend is configured. All methods are no-ops.
 */
class NoOpAgentMetrics : AgentMetrics {
    override fun recordExecution(result: AgentResult) {}
    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {}
    override fun recordGuardRejection(stage: String, reason: String) {}
}
