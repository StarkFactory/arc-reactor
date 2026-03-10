package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.resilience.CircuitBreakerState
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

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
     * Record a guard rejection with request metadata for tenant-aware metrics.
     *
     * Default implementation delegates to [recordGuardRejection] without metadata
     * to preserve backward compatibility with existing implementations.
     *
     * @param stage The guard stage that rejected the request
     * @param reason The rejection reason
     * @param metadata Request metadata (typically contains "tenantId")
     */
    fun recordGuardRejection(stage: String, reason: String, metadata: Map<String, Any>) {
        recordGuardRejection(stage, reason)
    }

    /**
     * Record a response cache hit.
     *
     * @param cacheKey The cache key that was hit
     */
    fun recordCacheHit(cacheKey: String) {}

    /**
     * Record an exact response cache hit (byte-identical request key match).
     *
     * Default implementation delegates to [recordCacheHit] for backward compatibility.
     *
     * @param cacheKey The cache key that was hit
     */
    fun recordExactCacheHit(cacheKey: String) {
        recordCacheHit(cacheKey)
    }

    /**
     * Record a semantic response cache hit (similar prompt match).
     *
     * Default implementation delegates to [recordCacheHit] for backward compatibility.
     *
     * @param cacheKey The exact cache key derived from the current request
     */
    fun recordSemanticCacheHit(cacheKey: String) {
        recordCacheHit(cacheKey)
    }

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
     * Record LLM token usage with request metadata for tenant-aware metrics.
     *
     * Default implementation delegates to [recordTokenUsage] without metadata
     * to preserve backward compatibility with existing implementations.
     *
     * @param usage The token usage from the LLM response
     * @param metadata Request metadata (typically contains "tenantId")
     */
    fun recordTokenUsage(usage: TokenUsage, metadata: Map<String, Any>) {
        recordTokenUsage(usage)
    }

    /**
     * Record a streaming execution result.
     *
     * Separate from [recordExecution] to allow distinguishing streaming vs non-streaming metrics.
     *
     * @param result The streaming execution result
     */
    fun recordStreamingExecution(result: AgentResult) {}

    /**
     * Record an output guard action.
     *
     * @param stage The output guard stage name
     * @param action The action taken: "allowed", "modified", or "rejected"
     * @param reason The reason for the action
     */
    fun recordOutputGuardAction(stage: String, action: String, reason: String) {}

    /**
     * Record an output guard action with request metadata for tenant-aware metrics.
     *
     * Default implementation delegates to [recordOutputGuardAction] without metadata
     * to preserve backward compatibility with existing implementations.
     *
     * @param stage The output guard stage name
     * @param action The action taken: "allowed", "modified", or "rejected"
     * @param reason The reason for the action
     * @param metadata Request metadata (typically contains "tenantId")
     */
    fun recordOutputGuardAction(stage: String, action: String, reason: String, metadata: Map<String, Any>) {
        recordOutputGuardAction(stage, action, reason)
    }

    /**
     * Record a boundary policy violation.
     *
     * @param violation The violation type (e.g., "output_too_short", "output_too_long")
     * @param policy The policy action taken (e.g., "warn", "retry_once", "fail", "truncate")
     * @param limit The configured limit value
     * @param actual The actual measured value
     */
    fun recordBoundaryViolation(violation: String, policy: String, limit: Int, actual: Int) {}

    /**
     * Record a boundary policy violation with request metadata for drill-down.
     *
     * Default implementation delegates to [recordBoundaryViolation] to preserve
     * backward compatibility with existing implementations.
     */
    fun recordBoundaryViolation(
        violation: String,
        policy: String,
        limit: Int,
        actual: Int,
        metadata: Map<String, Any>
    ) {
        recordBoundaryViolation(violation, policy, limit, actual)
    }

    /**
     * Record a response that could not be verified from approved sources.
     *
     * Default implementation is a no-op to preserve backward compatibility.
     *
     * @param metadata Request metadata (typically contains tenant/channel info)
     */
    fun recordUnverifiedResponse(metadata: Map<String, Any>) {}

    /**
     * Record a final response observation for product-value insights.
     *
     * Default implementation is a no-op to preserve backward compatibility.
     */
    fun recordResponseObservation(metadata: Map<String, Any>) {}

    /**
     * Record a stage-level latency for request execution analysis.
     *
     * Default implementation is a no-op to preserve backward compatibility.
     */
    fun recordStageLatency(stage: String, durationMs: Long, metadata: Map<String, Any>) {}
}

/**
 * No-op metrics implementation (default).
 *
 * Used when no metrics backend is configured. All methods are no-ops.
 */
class NoOpAgentMetrics : AgentMetrics, RecentTrustEventReader {
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
    private val channelCounts = ConcurrentHashMap<String, AtomicLong>()
    private val toolFamilyCounts = ConcurrentHashMap<String, AtomicLong>()
    private val laneSummaries = ConcurrentHashMap<String, ResponseLaneAggregate>()
    private val missingQueryCounts = ConcurrentHashMap<String, MissingQueryAggregate>()

    override fun recordExecution(result: AgentResult) {}
    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {}
    override fun recordGuardRejection(stage: String, reason: String) {}

    override fun recordOutputGuardAction(stage: String, action: String, reason: String, metadata: Map<String, Any>) {
        if (action.equals("rejected", ignoreCase = true)) {
            outputGuardRejected.incrementAndGet()
        } else if (action.equals("modified", ignoreCase = true)) {
            outputGuardModified.incrementAndGet()
        }
        if (!action.equals("allowed", ignoreCase = true)) {
            appendTrustEvent(
                RecentTrustEvent(
                    occurredAt = Instant.now(),
                    type = "output_guard",
                    severity = if (action.equals("rejected", ignoreCase = true)) "FAIL" else "WARN",
                    action = action,
                    stage = stage,
                    reason = metadata["blockReason"]?.toString()?.takeIf { it.isNotBlank() } ?: reason.takeIf { it.isNotBlank() },
                    channel = metadata["channel"]?.toString(),
                    queryCluster = metadata["queryCluster"]?.toString(),
                    queryLabel = metadata["queryLabel"]?.toString()
                )
            )
        }
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
        appendTrustEvent(
            RecentTrustEvent(
                occurredAt = Instant.now(),
                type = "boundary_violation",
                severity = if (policy.equals("fail", ignoreCase = true)) "FAIL" else "WARN",
                violation = violation,
                policy = policy,
                channel = metadata["channel"]?.toString(),
                queryCluster = metadata["queryCluster"]?.toString(),
                queryLabel = metadata["queryLabel"]?.toString()
            )
        )
    }

    override fun recordUnverifiedResponse(metadata: Map<String, Any>) {
        unverifiedResponses.incrementAndGet()
        appendTrustEvent(
            RecentTrustEvent(
                occurredAt = Instant.now(),
                type = "unverified_response",
                severity = "WARN",
                reason = metadata["blockReason"]?.toString(),
                channel = metadata["channel"]?.toString(),
                queryCluster = metadata["queryCluster"]?.toString(),
                queryLabel = metadata["queryLabel"]?.toString()
            )
        )
    }

    override fun recordResponseObservation(metadata: Map<String, Any>) {
        val answerMode = metadata["answerMode"]?.toString()?.trim()?.ifBlank { "unknown" } ?: "unknown"
        val grounded = metadata["grounded"] == true
        val blocked = metadata["blockReason"]?.toString()?.isNotBlank() == true
        observedResponses.incrementAndGet()
        if (grounded) groundedResponses.incrementAndGet()
        if (metadata["deliveryMode"] == "scheduled") scheduledResponses.incrementAndGet() else interactiveResponses.incrementAndGet()
        if (blocked) blockedResponses.incrementAndGet()
        incrementBucket(answerModeCounts, answerMode, "unknown")
        incrementBucket(channelCounts, metadata["channel"]?.toString(), "unknown")
        incrementBucket(toolFamilyCounts, metadata["toolFamily"]?.toString(), "none")
        trackLaneSummary(answerMode, grounded, blocked)
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
            channelCounts = snapshotCounts(channelCounts),
            toolFamilyCounts = snapshotCounts(toolFamilyCounts),
            laneSummaries = snapshotLaneSummaries()
        )
    }

    override fun topMissingQueries(limit: Int): List<MissingQueryInsight> {
        return missingQueryCounts.values
            .sortedWith(compareByDescending<MissingQueryAggregate> { it.count.get() }.thenByDescending { it.lastOccurredAt })
            .take(limit)
            .map {
                MissingQueryInsight(
                    queryCluster = it.queryCluster,
                    queryLabel = it.queryLabel,
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
        val blockReason = metadata["blockReason"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return
        val queryCluster = metadata["queryCluster"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return
        val queryLabel = metadata["queryLabel"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return
        val aggregate = missingQueryCounts.computeIfAbsent(queryCluster) {
            MissingQueryAggregate(queryCluster = queryCluster, queryLabel = queryLabel, blockReason = blockReason)
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

    private fun trackLaneSummary(answerMode: String, grounded: Boolean, blocked: Boolean) {
        val aggregate = laneSummaries.computeIfAbsent(answerMode) { ResponseLaneAggregate() }
        aggregate.observedResponses.incrementAndGet()
        if (grounded) aggregate.groundedResponses.incrementAndGet()
        if (blocked) aggregate.blockedResponses.incrementAndGet()
    }

    private fun snapshotLaneSummaries(): List<ResponseLaneSummary> {
        return laneSummaries.entries
            .sortedByDescending { it.value.observedResponses.get() }
            .map { (answerMode, aggregate) ->
                ResponseLaneSummary(
                    answerMode = answerMode,
                    observedResponses = aggregate.observedResponses.get(),
                    groundedResponses = aggregate.groundedResponses.get(),
                    blockedResponses = aggregate.blockedResponses.get()
                )
            }
    }

    companion object {
        private const val MAX_TRUST_EVENTS = 100
    }
}
