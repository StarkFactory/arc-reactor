package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.GuardEvent
import com.arc.reactor.admin.model.TokenUsageEvent
import com.arc.reactor.admin.pricing.CostCalculator
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.resilience.CircuitBreakerState
import mu.KotlinLogging
import java.math.BigDecimal
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * AgentMetrics implementation that publishes events to MetricRingBuffer.
 *
 * Replaces NoOpAgentMetrics as @Primary when arc-admin is enabled.
 * All methods are non-blocking (ring buffer publish is lock-free).
 */
class MetricCollectorAgentMetrics(
    private val ringBuffer: MetricRingBuffer,
    private val healthMonitor: PipelineHealthMonitor,
    private val costCalculator: CostCalculator
) : AgentMetrics {

    // Execution events are handled by MetricCollectionHook (richer data: latency breakdown, sessionId, etc.)
    override fun recordExecution(result: AgentResult) {}

    // Tool call events are handled by MetricCollectionHook (richer data: runId, toolSource, mcpServerName)
    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {}

    // Legacy no-metadata overloads — kept for backward compatibility but use "default" tenantId
    override fun recordGuardRejection(stage: String, reason: String) {
        recordGuardRejection(stage, reason, emptyMap())
    }

    override fun recordGuardRejection(stage: String, reason: String, metadata: Map<String, Any>) {
        val event = GuardEvent(
            tenantId = resolveTenantId(metadata),
            stage = stage,
            category = classifyGuardRejection(stage, reason),
            reasonDetail = reason.take(500)
        )
        publish(event)
    }

    override fun recordTokenUsage(usage: TokenUsage) {
        recordTokenUsage(usage, emptyMap())
    }

    override fun recordTokenUsage(usage: TokenUsage, metadata: Map<String, Any>) {
        val model = metadata["model"]?.toString() ?: "unknown"
        val provider = metadata["provider"]?.toString() ?: deriveProvider(model)
        val cost = try {
            costCalculator.calculate(
                provider = provider,
                model = model,
                time = Instant.now(),
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens
            )
        } catch (e: Exception) {
            logger.debug(e) { "Cost calculation failed for $model" }
            BigDecimal.ZERO
        }
        val event = TokenUsageEvent(
            tenantId = resolveTenantId(metadata),
            runId = metadata["runId"]?.toString().orEmpty(),
            model = model,
            provider = provider,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            totalTokens = usage.totalTokens,
            estimatedCostUsd = cost
        )
        publish(event)
    }

    // Streaming execution events are handled by MetricCollectionHook
    override fun recordStreamingExecution(result: AgentResult) {}

    override fun recordOutputGuardAction(stage: String, action: String, reason: String) {
        recordOutputGuardAction(stage, action, reason, emptyMap())
    }

    override fun recordOutputGuardAction(stage: String, action: String, reason: String, metadata: Map<String, Any>) {
        val event = GuardEvent(
            tenantId = resolveTenantId(metadata),
            stage = stage,
            category = "output_guard",
            reasonDetail = reason.take(500),
            isOutputGuard = true,
            action = action
        )
        publish(event)
    }

    override fun recordCacheHit(cacheKey: String) { /* tracked separately */ }
    override fun recordCacheMiss(cacheKey: String) { /* tracked separately */ }
    override fun recordCircuitBreakerStateChange(name: String, from: CircuitBreakerState, to: CircuitBreakerState) {}
    override fun recordFallbackAttempt(model: String, success: Boolean) {}
    override fun recordBoundaryViolation(violation: String, policy: String, limit: Int, actual: Int) {}

    private fun resolveTenantId(metadata: Map<String, Any>): String {
        return metadata["tenantId"]?.toString() ?: "default"
    }

    private fun publish(event: com.arc.reactor.admin.model.MetricEvent) {
        if (!ringBuffer.publish(event)) {
            healthMonitor.recordDrop(1)
            logger.debug { "MetricRingBuffer full, event dropped" }
        }
    }

    private fun deriveProvider(model: String): String {
        return when {
            model.startsWith("gpt-") || model.startsWith("o1") || model.startsWith("o3") -> "openai"
            model.startsWith("claude-") -> "anthropic"
            model.startsWith("gemini-") -> "google"
            model.startsWith("mistral") || model.startsWith("codestral") -> "mistral"
            model.startsWith("command") -> "cohere"
            model.contains("llama") -> "meta"
            else -> "unknown"
        }
    }

    private fun classifyGuardRejection(stage: String, reason: String): String {
        return when {
            stage.contains("RateLimit", ignoreCase = true) -> "rate_limit"
            stage.contains("Injection", ignoreCase = true) -> "prompt_injection"
            stage.contains("Classification", ignoreCase = true) -> "classification"
            stage.contains("Permission", ignoreCase = true) -> "permission"
            stage.contains("InputValidation", ignoreCase = true) -> "input_validation"
            else -> "other"
        }
    }
}
