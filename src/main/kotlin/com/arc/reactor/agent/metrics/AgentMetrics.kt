package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentResult

/**
 * Agent metrics interface for observability.
 *
 * Provides a framework-agnostic abstraction for recording agent execution metrics.
 * Users can implement this with Micrometer, Prometheus, or any metrics backend.
 *
 * ## Micrometer Example
 * ```kotlin
 * class MicrometerAgentMetrics(private val registry: MeterRegistry) : AgentMetrics {
 *     private val executionCounter = registry.counter("arc.agent.executions")
 *     private val executionTimer = registry.timer("arc.agent.execution.duration")
 *     private val errorCounter = registry.counter("arc.agent.errors")
 *     private val toolCounter = registry.counter("arc.agent.tool.calls")
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
