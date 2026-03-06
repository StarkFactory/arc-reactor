package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.resilience.CircuitBreakerState
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

class MicrometerAgentMetrics(
    private val registry: MeterRegistry
) : AgentMetrics {

    override fun recordExecution(result: AgentResult) {
        Counter.builder(METRIC_EXECUTIONS)
            .tag("success", result.success.toString())
            .tag("error_code", result.errorCode?.name ?: "none")
            .register(registry)
            .increment()

        Timer.builder(METRIC_EXECUTION_DURATION)
            .tag("success", result.success.toString())
            .register(registry)
            .record(result.durationMs, TimeUnit.MILLISECONDS)

        if (!result.success) {
            Counter.builder(METRIC_ERRORS)
                .tag("error_code", result.errorCode?.name ?: "unknown")
                .register(registry)
                .increment()
        }
    }

    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {
        Counter.builder(METRIC_TOOL_CALLS)
            .tag("tool", toolName)
            .tag("success", success.toString())
            .register(registry)
            .increment()

        Timer.builder(METRIC_TOOL_DURATION)
            .tag("tool", toolName)
            .tag("success", success.toString())
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }

    override fun recordGuardRejection(stage: String, reason: String) {
        Counter.builder(METRIC_GUARD_REJECTIONS)
            .tag("stage", stage)
            .register(registry)
            .increment()
    }

    override fun recordExactCacheHit(cacheKey: String) {
        Counter.builder(METRIC_CACHE_HITS)
            .tag("mode", "exact")
            .register(registry)
            .increment()
    }

    override fun recordSemanticCacheHit(cacheKey: String) {
        Counter.builder(METRIC_CACHE_HITS)
            .tag("mode", "semantic")
            .register(registry)
            .increment()
    }

    override fun recordCacheMiss(cacheKey: String) {
        Counter.builder(METRIC_CACHE_MISSES)
            .register(registry)
            .increment()
    }

    override fun recordCircuitBreakerStateChange(name: String, from: CircuitBreakerState, to: CircuitBreakerState) {
        Counter.builder(METRIC_CIRCUIT_BREAKER_TRANSITIONS)
            .tag("name", name)
            .tag("from", from.name)
            .tag("to", to.name)
            .register(registry)
            .increment()
    }

    override fun recordFallbackAttempt(model: String, success: Boolean) {
        Counter.builder(METRIC_FALLBACK_ATTEMPTS)
            .tag("model", model)
            .tag("success", success.toString())
            .register(registry)
            .increment()
    }

    override fun recordTokenUsage(usage: TokenUsage) {
        DistributionSummary.builder(METRIC_TOKENS_TOTAL)
            .baseUnit("tokens")
            .register(registry)
            .record(usage.totalTokens.toDouble())
    }

    override fun recordStreamingExecution(result: AgentResult) {
        Counter.builder(METRIC_STREAMING_EXECUTIONS)
            .tag("success", result.success.toString())
            .register(registry)
            .increment()
    }

    override fun recordOutputGuardAction(stage: String, action: String, reason: String) {
        Counter.builder(METRIC_OUTPUT_GUARD_ACTIONS)
            .tag("stage", stage)
            .tag("action", action)
            .register(registry)
            .increment()
    }

    override fun recordBoundaryViolation(violation: String, policy: String, limit: Int, actual: Int) {
        Counter.builder(METRIC_BOUNDARY_VIOLATIONS)
            .tag("violation", violation)
            .tag("policy", policy)
            .register(registry)
            .increment()
    }

    override fun recordUnverifiedResponse(metadata: Map<String, Any>) {
        Counter.builder(METRIC_UNVERIFIED_RESPONSES)
            .tag("channel", metadata["channel"]?.toString()?.ifBlank { "unknown" } ?: "unknown")
            .register(registry)
            .increment()
    }

    companion object {
        private const val METRIC_EXECUTIONS = "arc.agent.executions"
        private const val METRIC_EXECUTION_DURATION = "arc.agent.execution.duration"
        private const val METRIC_ERRORS = "arc.agent.errors"
        private const val METRIC_TOOL_CALLS = "arc.agent.tool.calls"
        private const val METRIC_TOOL_DURATION = "arc.agent.tool.duration"
        private const val METRIC_GUARD_REJECTIONS = "arc.agent.guard.rejections"
        private const val METRIC_CACHE_HITS = "arc.agent.cache.hits"
        private const val METRIC_CACHE_MISSES = "arc.agent.cache.misses"
        private const val METRIC_CIRCUIT_BREAKER_TRANSITIONS = "arc.agent.circuit_breaker.transitions"
        private const val METRIC_FALLBACK_ATTEMPTS = "arc.agent.fallback.attempts"
        private const val METRIC_TOKENS_TOTAL = "arc.agent.tokens.total"
        private const val METRIC_STREAMING_EXECUTIONS = "arc.agent.streaming.executions"
        private const val METRIC_OUTPUT_GUARD_ACTIONS = "arc.agent.output.guard.actions"
        private const val METRIC_BOUNDARY_VIOLATIONS = "arc.agent.boundary.violations"
        private const val METRIC_UNVERIFIED_RESPONSES = "arc.agent.responses.unverified"
    }
}
