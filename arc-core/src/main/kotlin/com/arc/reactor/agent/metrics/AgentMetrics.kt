package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.resilience.CircuitBreakerState
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 에이전트 메트릭 인터페이스 — 관측성(observability)을 위한 추상화.
 *
 * 에이전트 실행 메트릭을 기록하기 위한 프레임워크 비종속 추상화를 제공한다.
 * Micrometer, Prometheus, 또는 기타 메트릭 백엔드로 구현할 수 있다.
 *
 * 2.7.0에서 추가된 모든 새 메서드는 기존 사용자 구현과의 하위 호환성을 위해
 * 기본 빈 구현을 가진다.
 *
 * ## Micrometer 구현 예시
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
 * @see NoOpAgentMetrics 기본 no-op 구현체
 * @see MicrometerAgentMetrics Micrometer 기반 구현체
 */
interface AgentMetrics {
    /**
     * 에이전트 실행 결과를 기록한다.
     *
     * @param result 에이전트 실행 결과
     */
    fun recordExecution(result: AgentResult)

    /**
     * 도구 호출을 기록한다.
     *
     * @param toolName 호출된 도구 이름
     * @param durationMs 도구 호출 소요 시간 (밀리초)
     * @param success 도구 호출 성공 여부
     */
    fun recordToolCall(toolName: String, durationMs: Long, success: Boolean)

    /**
     * Guard 거부를 기록한다.
     *
     * @param stage 요청을 거부한 Guard 단계
     * @param reason 거부 사유
     */
    fun recordGuardRejection(stage: String, reason: String)

    /**
     * 테넌트 인식 메트릭을 위해 요청 메타데이터와 함께 Guard 거부를 기록한다.
     *
     * 기존 구현과의 하위 호환성을 위해 기본 구현은 메타데이터 없이
     * [recordGuardRejection]에 위임한다.
     *
     * @param stage 요청을 거부한 Guard 단계
     * @param reason 거부 사유
     * @param metadata 요청 메타데이터 (일반적으로 "tenantId" 포함)
     */
    fun recordGuardRejection(stage: String, reason: String, metadata: Map<String, Any>) {
        recordGuardRejection(stage, reason)
    }

    /**
     * 응답 캐시 히트를 기록한다.
     *
     * @param cacheKey 히트된 캐시 키
     */
    fun recordCacheHit(cacheKey: String) {}

    /**
     * 정확한 응답 캐시 히트(바이트 동일 요청 키 매칭)를 기록한다.
     *
     * 하위 호환성을 위해 기본 구현은 [recordCacheHit]에 위임한다.
     *
     * @param cacheKey 히트된 캐시 키
     */
    fun recordExactCacheHit(cacheKey: String) {
        recordCacheHit(cacheKey)
    }

    /**
     * 시맨틱 응답 캐시 히트(유사 프롬프트 매칭)를 기록한다.
     *
     * 하위 호환성을 위해 기본 구현은 [recordCacheHit]에 위임한다.
     *
     * @param cacheKey 현재 요청에서 파생된 정확한 캐시 키
     */
    fun recordSemanticCacheHit(cacheKey: String) {
        recordCacheHit(cacheKey)
    }

    /**
     * 응답 캐시 미스를 기록한다.
     *
     * @param cacheKey 미스된 캐시 키
     */
    fun recordCacheMiss(cacheKey: String) {}

    /**
     * 서킷 브레이커 상태 전환을 기록한다.
     *
     * @param name 서킷 브레이커 이름
     * @param from 이전 상태
     * @param to 새 상태
     */
    fun recordCircuitBreakerStateChange(name: String, from: CircuitBreakerState, to: CircuitBreakerState) {}

    /**
     * 모델 폴백 시도를 기록한다.
     *
     * @param model 시도된 모델
     * @param success 시도 성공 여부
     */
    fun recordFallbackAttempt(model: String, success: Boolean) {}

    /**
     * 단일 요청의 LLM 토큰 사용량을 기록한다.
     *
     * 각 LLM 호출 후 응답 메타데이터의 토큰 수로 호출된다.
     *
     * @param usage LLM 응답의 토큰 사용량
     */
    fun recordTokenUsage(usage: TokenUsage) {}

    /**
     * 테넌트 인식 메트릭을 위해 요청 메타데이터와 함께 LLM 토큰 사용량을 기록한다.
     *
     * 기존 구현과의 하위 호환성을 위해 기본 구현은 메타데이터 없이
     * [recordTokenUsage]에 위임한다.
     *
     * @param usage LLM 응답의 토큰 사용량
     * @param metadata 요청 메타데이터 (일반적으로 "tenantId" 포함)
     */
    fun recordTokenUsage(usage: TokenUsage, metadata: Map<String, Any>) {
        recordTokenUsage(usage)
    }

    /**
     * 스트리밍 실행 결과를 기록한다.
     *
     * 스트리밍과 비스트리밍 메트릭을 구분하기 위해 [recordExecution]과 분리됨.
     *
     * @param result 스트리밍 실행 결과
     */
    fun recordStreamingExecution(result: AgentResult) {}

    /**
     * 출력 가드 동작을 기록한다.
     *
     * @param stage 출력 가드 단계 이름
     * @param action 수행된 동작: "allowed", "modified", 또는 "rejected"
     * @param reason 동작 사유
     */
    fun recordOutputGuardAction(stage: String, action: String, reason: String) {}

    /**
     * 테넌트 인식 메트릭을 위해 요청 메타데이터와 함께 출력 가드 동작을 기록한다.
     *
     * 기존 구현과의 하위 호환성을 위해 기본 구현은 메타데이터 없이
     * [recordOutputGuardAction]에 위임한다.
     *
     * @param stage 출력 가드 단계 이름
     * @param action 수행된 동작: "allowed", "modified", 또는 "rejected"
     * @param reason 동작 사유
     * @param metadata 요청 메타데이터 (일반적으로 "tenantId" 포함)
     */
    fun recordOutputGuardAction(stage: String, action: String, reason: String, metadata: Map<String, Any>) {
        recordOutputGuardAction(stage, action, reason)
    }

    /**
     * 경계값 정책 위반을 기록한다.
     *
     * @param violation 위반 유형 (예: "output_too_short", "output_too_long")
     * @param policy 수행된 정책 동작 (예: "warn", "retry_once", "fail", "truncate")
     * @param limit 설정된 제한값
     * @param actual 실제 측정값
     */
    fun recordBoundaryViolation(violation: String, policy: String, limit: Int, actual: Int) {}

    /**
     * 상세 분석을 위해 요청 메타데이터와 함께 경계값 정책 위반을 기록한다.
     *
     * 기존 구현과의 하위 호환성을 위해 기본 구현은
     * [recordBoundaryViolation]에 위임한다.
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
     * 승인된 출처에서 검증할 수 없는 응답을 기록한다.
     *
     * 하위 호환성을 위해 기본 구현은 no-op.
     *
     * @param metadata 요청 메타데이터 (일반적으로 테넌트/채널 정보 포함)
     */
    fun recordUnverifiedResponse(metadata: Map<String, Any>) {}

    /**
     * 제품 가치 인사이트를 위한 최종 응답 관측을 기록한다.
     *
     * 하위 호환성을 위해 기본 구현은 no-op.
     */
    fun recordResponseObservation(metadata: Map<String, Any>) {}

    /**
     * 요청 실행 분석을 위한 단계별 지연 시간을 기록한다.
     *
     * 하위 호환성을 위해 기본 구현은 no-op.
     */
    fun recordStageLatency(stage: String, durationMs: Long, metadata: Map<String, Any>) {}

    /**
     * SLA 추적(P50/P95/P99)을 위한 LLM 호출 지연 시간을 기록한다.
     *
     * 하위 호환성을 위해 기본 구현은 no-op.
     *
     * @param model LLM 모델 이름
     * @param durationMs LLM 호출 소요 시간 (밀리초)
     */
    fun recordLlmLatency(model: String, durationMs: Long) {}

    /**
     * 도구 결과 캐시 히트(같은 도구 + 같은 인자가 캐시에서 재사용)를 기록한다.
     *
     * @param toolName 캐시된 결과가 재사용된 도구 이름
     * @param cacheKey 히트된 캐시 키
     */
    fun recordToolResultCacheHit(toolName: String, cacheKey: String) {}

    /**
     * 도구 결과 캐시 미스(도구가 실행되고 결과가 캐시에 저장)를 기록한다.
     *
     * @param toolName 실행된 도구 이름
     * @param cacheKey 미스된 캐시 키
     */
    fun recordToolResultCacheMiss(toolName: String, cacheKey: String) {}

    /**
     * 모니터링 및 잘림 추적을 위한 도구 출력 크기를 기록한다.
     *
     * 하위 호환성을 위해 기본 구현은 no-op.
     *
     * @param toolName 도구 이름
     * @param sizeBytes 도구 출력 크기 (바이트)
     * @param truncated 출력이 잘렸는지 여부
     */
    fun recordToolOutputSize(toolName: String, sizeBytes: Int, truncated: Boolean) {}

    /**
     * 현재 활성(진행 중) 에이전트 요청 수를 기록한다.
     *
     * 하위 호환성을 위해 기본 구현은 no-op.
     *
     * @param count 현재 활성 요청 수
     */
    fun recordActiveRequests(count: Int) {}

    /**
     * 관측성을 위한 RAG 검색 결과를 기록한다.
     *
     * 하위 호환성을 위해 기본 구현은 no-op.
     *
     * @param status 검색 상태: "success", "empty", "timeout", 또는 "error"
     * @param durationMs 검색 소요 시간 (밀리초)
     */
    fun recordRagRetrieval(status: String, durationMs: Long) {}
}

/**
 * No-op 메트릭 구현체 (기본값).
 *
 * 메트릭 백엔드가 설정되지 않은 경우 사용된다. 모든 메서드가 no-op.
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
                    reason = metadata["blockReason"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: reason.takeIf { it.isNotBlank() },
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
        if (metadata["deliveryMode"] == "scheduled") {
            scheduledResponses.incrementAndGet()
        } else {
            interactiveResponses.incrementAndGet()
        }
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
            .sortedWith(
                compareByDescending<MissingQueryAggregate> { it.count.get() }
                    .thenByDescending { it.lastOccurredAt }
            )
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
