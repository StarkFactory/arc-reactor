package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.resilience.CircuitBreakerState
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

class MicrometerAgentMetrics(
    private val registry: MeterRegistry
) : AgentMetrics, RecentTrustEventReader {

    private val trustEvents = ConcurrentLinkedDeque<RecentTrustEvent>()
    private val unverifiedResponses = AtomicLong()
    private val outputGuardRejected = AtomicLong()
    private val outputGuardModified = AtomicLong()
    private val boundaryFailures = AtomicLong()
    private val observedResponses = AtomicLong()
    private val groundedResponses = AtomicLong()
    private val blockedResponses = AtomicLong()
    private val interactiveResponses = AtomicLong()
    private val scheduledResponses = AtomicLong()
    private val answerModeCounts = ConcurrentHashMap<String, AtomicLong>()
    private val toolFamilyCounts = ConcurrentHashMap<String, AtomicLong>()
    private val missingQueryCounts = ConcurrentHashMap<String, MissingQueryAggregate>()

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
        recordOutputGuardAction(stage, action, reason, emptyMap())
    }

    override fun recordOutputGuardAction(stage: String, action: String, reason: String, metadata: Map<String, Any>) {
        Counter.builder(METRIC_OUTPUT_GUARD_ACTIONS)
            .tag("stage", stage)
            .tag("action", action)
            .register(registry)
            .increment()

        if (!action.equals("allowed", ignoreCase = true)) {
            if (action.equals("rejected", ignoreCase = true)) {
                outputGuardRejected.incrementAndGet()
            } else if (action.equals("modified", ignoreCase = true)) {
                outputGuardModified.incrementAndGet()
            }
            appendTrustEvent(
                RecentTrustEvent(
                    occurredAt = Instant.now(),
                    type = "output_guard",
                    severity = if (action.equals("rejected", ignoreCase = true)) "FAIL" else "WARN",
                    action = action,
                    stage = stage,
                    reason = reason.takeIf { it.isNotBlank() },
                    channel = metadataValue(metadata, "channel"),
                    runId = metadataValue(metadata, "runId"),
                    userId = metadataValue(metadata, "userId"),
                    queryPreview = metadataValue(metadata, "queryPreview")
                )
            )
        }
    }

    override fun recordBoundaryViolation(violation: String, policy: String, limit: Int, actual: Int) {
        recordBoundaryViolation(violation, policy, limit, actual, emptyMap())
    }

    override fun recordBoundaryViolation(
        violation: String,
        policy: String,
        limit: Int,
        actual: Int,
        metadata: Map<String, Any>
    ) {
        if (policy.equals("fail", ignoreCase = true)) {
            boundaryFailures.incrementAndGet()
        }
        Counter.builder(METRIC_BOUNDARY_VIOLATIONS)
            .tag("violation", violation)
            .tag("policy", policy)
            .register(registry)
            .increment()

        appendTrustEvent(
            RecentTrustEvent(
                occurredAt = Instant.now(),
                type = "boundary_violation",
                severity = if (policy.equals("fail", ignoreCase = true)) "FAIL" else "WARN",
                violation = violation,
                policy = policy,
                channel = metadataValue(metadata, "channel"),
                runId = metadataValue(metadata, "runId"),
                userId = metadataValue(metadata, "userId"),
                queryPreview = metadataValue(metadata, "queryPreview")
            )
        )
    }

    override fun recordUnverifiedResponse(metadata: Map<String, Any>) {
        unverifiedResponses.incrementAndGet()
        Counter.builder(METRIC_UNVERIFIED_RESPONSES)
            .tag("channel", metadata["channel"]?.toString()?.ifBlank { "unknown" } ?: "unknown")
            .register(registry)
            .increment()

        appendTrustEvent(
            RecentTrustEvent(
                occurredAt = Instant.now(),
                type = "unverified_response",
                severity = "WARN",
                channel = metadata["channel"]?.toString()?.ifBlank { "unknown" } ?: "unknown",
                runId = metadataValue(metadata, "runId"),
                userId = metadataValue(metadata, "userId"),
                queryPreview = metadataValue(metadata, "queryPreview"),
                reason = metadataValue(metadata, "blockReason")
            )
        )
    }

    override fun recordResponseObservation(metadata: Map<String, Any>) {
        observedResponses.incrementAndGet()
        if (metadata["grounded"] == true) groundedResponses.incrementAndGet()
        if (metadata["deliveryMode"] == "scheduled") scheduledResponses.incrementAndGet() else interactiveResponses.incrementAndGet()
        if (metadata["blockReason"]?.toString()?.isNotBlank() == true) blockedResponses.incrementAndGet()
        incrementBucket(answerModeCounts, metadata["answerMode"]?.toString(), "unknown")
        incrementBucket(toolFamilyCounts, metadata["toolFamily"]?.toString(), "none")
        trackMissingQuery(metadata)
    }

    override fun recentTrustEvents(limit: Int): List<RecentTrustEvent> = trustEvents.take(limit)
    override fun unverifiedResponsesCount(): Long = unverifiedResponses.get()
    override fun outputGuardRejectedCount(): Long = outputGuardRejected.get()
    override fun outputGuardModifiedCount(): Long = outputGuardModified.get()
    override fun boundaryFailuresCount(): Long = boundaryFailures.get()
    override fun responseValueSummary(): ResponseValueSummary {
        return ResponseValueSummary(
            observedResponses = observedResponses.get(),
            groundedResponses = groundedResponses.get(),
            blockedResponses = blockedResponses.get(),
            interactiveResponses = interactiveResponses.get(),
            scheduledResponses = scheduledResponses.get(),
            answerModeCounts = snapshotCounts(answerModeCounts),
            toolFamilyCounts = snapshotCounts(toolFamilyCounts)
        )
    }

    override fun topMissingQueries(limit: Int): List<MissingQueryInsight> {
        return missingQueryCounts.values
            .sortedWith(compareByDescending<MissingQueryAggregate> { it.count.get() }.thenByDescending { it.lastOccurredAt })
            .take(limit)
            .map {
                MissingQueryInsight(
                    queryPreview = it.queryPreview,
                    count = it.count.get(),
                    lastOccurredAt = it.lastOccurredAt,
                    blockReason = it.blockReason
                )
            }
    }

    private fun appendTrustEvent(event: RecentTrustEvent) {
        trustEvents.addFirst(event)
        while (trustEvents.size > MAX_TRUST_EVENTS) {
            trustEvents.pollLast()
        }
    }

    private fun trackMissingQuery(metadata: Map<String, Any>) {
        val blockReason = metadataValue(metadata, "blockReason") ?: return
        val queryPreview = metadataValue(metadata, "queryPreview") ?: return
        val aggregate = missingQueryCounts.computeIfAbsent(normalizeMissingQueryKey(queryPreview)) {
            MissingQueryAggregate(queryPreview = queryPreview, blockReason = blockReason)
        }
        aggregate.count.incrementAndGet()
        aggregate.lastOccurredAt = Instant.now()
    }

    private fun incrementBucket(
        counts: ConcurrentHashMap<String, AtomicLong>,
        rawKey: String?,
        fallback: String
    ) {
        val key = rawKey?.trim()?.ifBlank { fallback } ?: fallback
        counts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
    }

    private fun snapshotCounts(counts: ConcurrentHashMap<String, AtomicLong>): Map<String, Long> {
        return counts.entries
            .sortedByDescending { it.value.get() }
            .associate { it.key to it.value.get() }
    }

    private fun metadataValue(metadata: Map<String, Any>, key: String): String? {
        return metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val MAX_TRUST_EVENTS = 100
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
