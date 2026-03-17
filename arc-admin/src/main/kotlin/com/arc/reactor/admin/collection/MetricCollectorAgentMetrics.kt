package com.arc.reactor.admin.collection

import com.arc.reactor.admin.AdminClassifiers
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
 * [MetricRingBuffer]에 이벤트를 발행하는 [AgentMetrics] 구현체.
 *
 * arc-admin 활성화 시 `@Primary`로 NoOpAgentMetrics를 대체한다.
 * 모든 메서드는 논블로킹이다 (링 버퍼 publish는 lock-free).
 *
 * @see MetricCollectionHook 더 풍부한 컨텍스트(지연 분해, sessionId 등)를 제공하는 Hook
 */
class MetricCollectorAgentMetrics(
    private val ringBuffer: MetricRingBuffer,
    private val healthMonitor: PipelineHealthMonitor,
    private val costCalculator: CostCalculator
) : AgentMetrics {

    // 실행 이벤트는 MetricCollectionHook이 처리 (지연 분해, sessionId 등 더 풍부한 데이터)
    override fun recordExecution(result: AgentResult) {}

    // 도구 호출 이벤트는 MetricCollectionHook이 처리 (runId, toolSource, mcpServerName 등)
    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {}

    // 레거시 메타데이터 없는 오버로드 — 하위 호환성 유지, "default" tenantId 사용
    override fun recordGuardRejection(stage: String, reason: String) {
        recordGuardRejection(stage, reason, emptyMap())
    }

    override fun recordGuardRejection(stage: String, reason: String, metadata: Map<String, Any>) {
        val event = GuardEvent(
            tenantId = resolveTenantId(metadata),
            stage = stage,
            category = AdminClassifiers.classifyGuardStage(stage),
            reasonDetail = reason.take(500)
        )
        publish(event)
    }

    override fun recordTokenUsage(usage: TokenUsage) {
        recordTokenUsage(usage, emptyMap())
    }

    override fun recordTokenUsage(usage: TokenUsage, metadata: Map<String, Any>) {
        val model = metadata["model"]?.toString() ?: "unknown"
        val provider = metadata["provider"]?.toString() ?: AdminClassifiers.deriveProvider(model)
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

    // 스트리밍 실행 이벤트는 MetricCollectionHook이 처리
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

    override fun recordCacheHit(cacheKey: String) {
        healthMonitor.recordExactCacheHit()
    }

    override fun recordExactCacheHit(cacheKey: String) {
        healthMonitor.recordExactCacheHit()
    }

    override fun recordSemanticCacheHit(cacheKey: String) {
        healthMonitor.recordSemanticCacheHit()
    }

    override fun recordCacheMiss(cacheKey: String) {
        healthMonitor.recordCacheMiss()
    }
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

}
