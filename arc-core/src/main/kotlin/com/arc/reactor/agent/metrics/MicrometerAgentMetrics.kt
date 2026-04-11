package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.resilience.CircuitBreakerState
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/**
 * Micrometer 기반 [AgentMetrics] 및 [RecentTrustEventReader] 구현체.
 *
 * 에이전트 실행, 도구 호출, 가드 거부, 캐시, 서킷 브레이커, 토큰 사용량 등
 * 모든 관측 지표를 [MeterRegistry]에 기록한다.
 * 신뢰 이벤트(output guard, boundary violation, 미검증 응답)는 최근 [MAX_TRUST_EVENTS]건까지
 * 인메모리 덱(deque)에 보관하여 대시보드 조회를 지원한다.
 *
 * @param registry Micrometer 메트릭 레지스트리
 * @see AgentMetrics 메트릭 인터페이스
 * @see RecentTrustEventReader 신뢰 이벤트 읽기 인터페이스
 */
class MicrometerAgentMetrics(
    private val registry: MeterRegistry
) : AgentMetrics, RecentTrustEventReader {

    // -- 신뢰 이벤트 인메모리 저장소 --
    private val trustEvents = ConcurrentLinkedDeque<RecentTrustEvent>()

    // -- 카운터: 신뢰 관련 누적 수치 --
    private val unverifiedResponses = AtomicLong()
    private val outputGuardRejected = AtomicLong()
    private val outputGuardModified = AtomicLong()
    private val boundaryFailures = AtomicLong()

    // -- 응답 가치 분석용 카운터 --
    private val observedResponses = AtomicLong()
    private val groundedResponses = AtomicLong()
    private val blockedResponses = AtomicLong()
    private val interactiveResponses = AtomicLong()
    private val scheduledResponses = AtomicLong()
    /**
     * R282: 5개 ConcurrentHashMap → Caffeine bounded cache 마이그레이션 (CLAUDE.md 준수).
     * NoOpAgentMetrics와 동일한 마이그레이션. bound 근거 및 trade-off는
     * [NoOpAgentMetrics] R282 KDoc 참조.
     */
    private val answerModeCounts: com.github.benmanes.caffeine.cache.Cache<String, AtomicLong> =
        Caffeine.newBuilder().maximumSize(MAX_MODE_BUCKETS).build()
    private val channelCounts: com.github.benmanes.caffeine.cache.Cache<String, AtomicLong> =
        Caffeine.newBuilder().maximumSize(MAX_CHANNEL_BUCKETS).build()
    private val toolFamilyCounts: com.github.benmanes.caffeine.cache.Cache<String, AtomicLong> =
        Caffeine.newBuilder().maximumSize(MAX_FAMILY_BUCKETS).build()
    private val laneSummaries: com.github.benmanes.caffeine.cache.Cache<String, ResponseLaneAggregate> =
        Caffeine.newBuilder().maximumSize(MAX_LANE_BUCKETS).build()
    private val missingQueryCounts: com.github.benmanes.caffeine.cache.Cache<String, MissingQueryAggregate> =
        Caffeine.newBuilder().maximumSize(MAX_MISSING_QUERY_BUCKETS).build()

    // -- 현재 활성 요청 수 게이지 --
    private val activeRequestCount = AtomicInteger(0)

    init {
        registry.gauge(METRIC_ACTIVE_REQUESTS, activeRequestCount)
    }

    // ── 실행 메트릭 ──

    /** 에이전트 실행 결과를 카운터와 타이머에 기록한다. */
    override fun recordExecution(result: AgentResult) {
        Counter.builder(METRIC_EXECUTIONS)
            .tag("success", result.success.toString())
            .tag("error_code", result.errorCode?.name ?: "none")
            .register(registry)
            .increment()

        Timer.builder(METRIC_EXECUTION_DURATION)
            .tag("success", result.success.toString())
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)
            .record(result.durationMs, TimeUnit.MILLISECONDS)

        if (!result.success) {
            Counter.builder(METRIC_ERRORS)
                .tag("error_code", result.errorCode?.name ?: "unknown")
                .register(registry)
                .increment()
        }
    }

    // ── 도구 메트릭 ──

    /** 개별 도구 호출의 성공/실패 및 소요 시간을 기록한다. */
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

    // ── 가드 메트릭 ──

    /** 입력 가드 거부를 단계별로 기록한다. */
    override fun recordGuardRejection(stage: String, reason: String) {
        Counter.builder(METRIC_GUARD_REJECTIONS)
            .tag("stage", stage)
            .register(registry)
            .increment()
    }

    // ── 캐시 메트릭 ──

    /** 정확 일치 캐시 히트를 기록한다. */
    override fun recordExactCacheHit(cacheKey: String) {
        Counter.builder(METRIC_CACHE_HITS)
            .tag("mode", "exact")
            .register(registry)
            .increment()
    }

    /** 시맨틱 캐시 히트를 기록한다. */
    override fun recordSemanticCacheHit(cacheKey: String) {
        Counter.builder(METRIC_CACHE_HITS)
            .tag("mode", "semantic")
            .register(registry)
            .increment()
    }

    /** 캐시 미스를 기록한다. */
    override fun recordCacheMiss(cacheKey: String) {
        Counter.builder(METRIC_CACHE_MISSES)
            .register(registry)
            .increment()
    }

    // ── 서킷 브레이커 / 폴백 메트릭 ──

    /** 서킷 브레이커 상태 전환을 기록한다. */
    override fun recordCircuitBreakerStateChange(name: String, from: CircuitBreakerState, to: CircuitBreakerState) {
        Counter.builder(METRIC_CIRCUIT_BREAKER_TRANSITIONS)
            .tag("name", name)
            .tag("from", from.name)
            .tag("to", to.name)
            .register(registry)
            .increment()
    }

    /** 모델 폴백 시도를 기록한다. */
    override fun recordFallbackAttempt(model: String, success: Boolean) {
        Counter.builder(METRIC_FALLBACK_ATTEMPTS)
            .tag("model", model)
            .tag("success", success.toString())
            .register(registry)
            .increment()
    }

    // ── 토큰 / 스트리밍 메트릭 ──

    /** 토큰 사용량을 분포 요약 지표로 기록한다. */
    override fun recordTokenUsage(usage: TokenUsage) {
        DistributionSummary.builder(METRIC_TOKENS_TOTAL)
            .baseUnit("tokens")
            .register(registry)
            .record(usage.totalTokens.toDouble())
    }

    /** 스트리밍 실행 결과를 기록한다. */
    override fun recordStreamingExecution(result: AgentResult) {
        Counter.builder(METRIC_STREAMING_EXECUTIONS)
            .tag("success", result.success.toString())
            .register(registry)
            .increment()
    }

    // ── 출력 가드 메트릭 ──

    /** 출력 가드 액션을 기록한다 (메타데이터 없는 오버로드). */
    override fun recordOutputGuardAction(stage: String, action: String, reason: String) {
        recordOutputGuardAction(stage, action, reason, emptyMap())
    }

    /**
     * 출력 가드 액션을 기록하고, 거부/수정 시 신뢰 이벤트를 추가한다.
     *
     * @param stage 가드 단계 이름
     * @param action 수행된 액션 (allowed, rejected, modified)
     * @param reason 거부/수정 사유
     * @param metadata 채널, 쿼리 클러스터 등 부가 정보
     */
    override fun recordOutputGuardAction(stage: String, action: String, reason: String, metadata: Map<String, Any>) {
        Counter.builder(METRIC_OUTPUT_GUARD_ACTIONS)
            .tag("stage", stage)
            .tag("action", action)
            .register(registry)
            .increment()

        // allowed가 아닌 경우에만 신뢰 이벤트 기록
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
                    queryCluster = metadataValue(metadata, "queryCluster"),
                    queryLabel = metadataValue(metadata, "queryLabel")
                )
            )
        }
    }

    // ── 경계 위반 메트릭 ──

    /** 경계 위반을 기록한다 (메타데이터 없는 오버로드). */
    override fun recordBoundaryViolation(violation: String, policy: String, limit: Int, actual: Int) {
        recordBoundaryViolation(violation, policy, limit, actual, emptyMap())
    }

    /**
     * 경계 위반을 기록하고 신뢰 이벤트를 추가한다.
     *
     * @param violation 위반 유형
     * @param policy 적용된 정책 (fail, warn 등)
     * @param limit 허용 한도
     * @param actual 실제 값
     * @param metadata 부가 정보
     */
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
                queryCluster = metadataValue(metadata, "queryCluster"),
                queryLabel = metadataValue(metadata, "queryLabel")
            )
        )
    }

    // ── 미검증 응답 메트릭 ──

    /** 미검증 응답을 기록하고 신뢰 이벤트를 추가한다. */
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
                queryCluster = metadataValue(metadata, "queryCluster"),
                queryLabel = metadataValue(metadata, "queryLabel"),
                reason = metadataValue(metadata, "blockReason")
            )
        )
    }

    // ── 응답 관측 메트릭 ──

    /**
     * 응답을 관측하여 응답 가치 분석에 필요한 집계 데이터를 갱신한다.
     *
     * 응답 모드, 근거 여부, 차단 여부, 전달 방식(인터랙티브/스케줄), 채널, 도구 계열 등을 분류한다.
     */
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

        // 버킷별 카운트 갱신
        incrementBucket(answerModeCounts.asMap(), answerMode, "unknown")
        incrementBucket(channelCounts.asMap(), metadata["channel"]?.toString(), "unknown")
        incrementBucket(toolFamilyCounts.asMap(), metadata["toolFamily"]?.toString(), "none")
        trackLaneSummary(answerMode, grounded, blocked)
        trackMissingQuery(metadata)
    }

    // ── LLM 지연시간 메트릭 ──

    /** 모델별 LLM 호출 지연시간을 기록한다. */
    override fun recordLlmLatency(model: String, durationMs: Long) {
        Timer.builder(METRIC_LLM_LATENCY)
            .tag("model", model)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)
            .record(durationMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    // ── 도구 출력 크기 메트릭 ──

    /** 도구 출력 크기를 기록하고, 잘림(truncation) 발생 시 별도 카운터를 증가시킨다. */
    override fun recordToolOutputSize(toolName: String, sizeBytes: Int, truncated: Boolean) {
        DistributionSummary.builder(METRIC_TOOL_OUTPUT_SIZE)
            .baseUnit("bytes")
            .tag("tool", toolName)
            .register(registry)
            .record(sizeBytes.coerceAtLeast(0).toDouble())

        if (truncated) {
            Counter.builder(METRIC_TOOL_OUTPUT_TRUNCATED)
                .tag("tool", toolName)
                .register(registry)
                .increment()
        }
    }

    /** 현재 활성 요청 수를 갱신한다. */
    override fun recordActiveRequests(count: Int) {
        activeRequestCount.set(count)
    }

    // ── 도구 결과 캐시 메트릭 ──

    /** 도구 결과 캐시 히트를 기록한다. */
    override fun recordToolResultCacheHit(toolName: String, cacheKey: String) {
        Counter.builder(METRIC_TOOL_RESULT_CACHE_HITS)
            .tag("tool", toolName)
            .register(registry)
            .increment()
    }

    /** 도구 결과 캐시 미스를 기록한다. */
    override fun recordToolResultCacheMiss(toolName: String, cacheKey: String) {
        Counter.builder(METRIC_TOOL_RESULT_CACHE_MISSES)
            .tag("tool", toolName)
            .register(registry)
            .increment()
    }

    // ── 비용 메트릭 ──

    /**
     * 요청당 추정 비용(USD)을 모델별·테넌트별로 기록한다.
     *
     * - `arc.agent.request.cost`: 요청별 비용 분포 (model, tenantId 태그)
     * - `arc.agent.cost.total.usd`: 누적 총 비용 카운터
     */
    override fun recordRequestCost(costUsd: Double, model: String, metadata: Map<String, Any>) {
        val tenantId = metadata["tenantId"]?.toString()?.ifBlank { "unknown" } ?: "unknown"

        DistributionSummary.builder(METRIC_REQUEST_COST)
            .baseUnit("usd")
            .tag("model", model)
            .tag("tenantId", tenantId)
            .register(registry)
            .record(costUsd.coerceAtLeast(0.0))

        Counter.builder(METRIC_COST_TOTAL)
            .baseUnit("usd")
            .tag("model", model)
            .tag("tenantId", tenantId)
            .register(registry)
            .increment(costUsd.coerceAtLeast(0.0))
    }

    // ── RAG 검색 메트릭 ──

    /** RAG 검색 결과 및 소요 시간을 기록한다. */
    override fun recordRagRetrieval(status: String, durationMs: Long) {
        Counter.builder(METRIC_RAG_RETRIEVALS)
            .tag("status", status)
            .register(registry)
            .increment()

        Timer.builder(METRIC_RAG_RETRIEVAL_DURATION)
            .tag("status", status)
            .register(registry)
            .record(durationMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    // ── 단계별 지연시간 메트릭 ──

    /** 파이프라인 단계별 지연시간을 채널 태그와 함께 기록한다. */
    override fun recordStageLatency(stage: String, durationMs: Long, metadata: Map<String, Any>) {
        Timer.builder(METRIC_STAGE_DURATION)
            .tag("stage", stage)
            .tag("channel", metadata["channel"]?.toString()?.trim()?.ifBlank { "unknown" } ?: "unknown")
            .register(registry)
            .record(durationMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    // ── RecentTrustEventReader 구현 ──

    override fun recentTrustEvents(limit: Int): List<RecentTrustEvent> = trustEvents.take(limit)
    override fun unverifiedResponsesCount(): Long = unverifiedResponses.get()
    override fun outputGuardRejectedCount(): Long = outputGuardRejected.get()
    override fun outputGuardModifiedCount(): Long = outputGuardModified.get()
    override fun boundaryFailuresCount(): Long = boundaryFailures.get()

    /** 응답 가치 요약 스냅샷을 반환한다. */
    override fun responseValueSummary(): ResponseValueSummary {
        return ResponseValueSummary(
            observedResponses = observedResponses.get(),
            groundedResponses = groundedResponses.get(),
            blockedResponses = blockedResponses.get(),
            interactiveResponses = interactiveResponses.get(),
            scheduledResponses = scheduledResponses.get(),
            answerModeCounts = snapshotCounts(answerModeCounts.asMap()),
            channelCounts = snapshotCounts(channelCounts.asMap()),
            toolFamilyCounts = snapshotCounts(toolFamilyCounts.asMap()),
            laneSummaries = snapshotLaneSummaries()
        )
    }

    /** 누락 쿼리 상위 [limit]건을 빈도 내림차순으로 반환한다. */
    override fun topMissingQueries(limit: Int): List<MissingQueryInsight> {
        return missingQueryCounts.asMap().values
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

    // ── 내부 유틸리티 ──

    /** 신뢰 이벤트를 덱 앞에 추가하고 최대 크기를 유지한다. */
    private fun appendTrustEvent(event: RecentTrustEvent) {
        trustEvents.addFirst(event)
        while (trustEvents.size > MAX_TRUST_EVENTS) {
            trustEvents.pollLast()
        }
    }

    /** 차단된 쿼리의 클러스터별 집계를 갱신한다. */
    private fun trackMissingQuery(metadata: Map<String, Any>) {
        val blockReason = metadataValue(metadata, "blockReason") ?: return
        val queryCluster = metadataValue(metadata, "queryCluster") ?: return
        val queryLabel = metadataValue(metadata, "queryLabel") ?: return
        val aggregate = missingQueryCounts.asMap().computeIfAbsent(queryCluster) {
            MissingQueryAggregate(queryCluster = queryCluster, queryLabel = queryLabel, blockReason = blockReason)
        }
        aggregate.count.incrementAndGet()
        aggregate.lastOccurredAt = Instant.now()
    }

    /** 동시성 안전한 버킷 카운터를 1 증가시킨다. */
    private fun incrementBucket(
        counts: ConcurrentMap<String, AtomicLong>,
        rawKey: String?,
        fallback: String
    ) {
        val key = rawKey?.trim()?.ifBlank { fallback } ?: fallback
        counts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
    }

    /** 버킷 카운터의 현재 스냅샷을 빈도 내림차순 맵으로 반환한다. */
    private fun snapshotCounts(counts: ConcurrentMap<String, AtomicLong>): Map<String, Long> {
        return counts.entries
            .sortedByDescending { it.value.get() }
            .associate { it.key to it.value.get() }
    }

    /** 응답 모드별 레인 집계를 갱신한다. */
    private fun trackLaneSummary(answerMode: String, grounded: Boolean, blocked: Boolean) {
        val aggregate = laneSummaries.asMap().computeIfAbsent(answerMode) { ResponseLaneAggregate() }
        aggregate.observedResponses.incrementAndGet()
        if (grounded) aggregate.groundedResponses.incrementAndGet()
        if (blocked) aggregate.blockedResponses.incrementAndGet()
    }

    /** 레인 집계의 현재 스냅샷을 관측 수 내림차순 리스트로 반환한다. */
    private fun snapshotLaneSummaries(): List<ResponseLaneSummary> {
        return laneSummaries.asMap().entries
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

    /** 메타데이터에서 문자열 값을 추출한다. 공백이면 `null` 반환. */
    private fun metadataValue(metadata: Map<String, Any>, key: String): String? {
        return metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val MAX_TRUST_EVENTS = 100
        /** R282: 응답 모드는 enum-like, 100 슬롯 */
        private const val MAX_MODE_BUCKETS = 100L
        /** R282: Slack 채널 ID는 카디널리티 높음, 10000 슬롯 */
        private const val MAX_CHANNEL_BUCKETS = 10_000L
        /** R282: 도구 계열은 수십~수백 개, 500 슬롯 */
        private const val MAX_FAMILY_BUCKETS = 500L
        /** R282: 응답 lane은 mode와 1:1, 100 슬롯 */
        private const val MAX_LANE_BUCKETS = 100L
        /** R282: queryCluster는 사용자 입력 카디널리티, 10000 슬롯 */
        private const val MAX_MISSING_QUERY_BUCKETS = 10_000L
        private const val METRIC_EXECUTIONS = "arc.agent.executions"
        private const val METRIC_EXECUTION_DURATION = "arc.agent.execution.duration"
        private const val METRIC_ERRORS = "arc.agent.errors"
        private const val METRIC_TOOL_CALLS = "arc.agent.tool.calls"
        private const val METRIC_TOOL_DURATION = "arc.agent.tool.duration"
        private const val METRIC_GUARD_REJECTIONS = "arc.agent.guard.rejections"
        private const val METRIC_CACHE_HITS = "arc.agent.cache.hits"
        private const val METRIC_CACHE_MISSES = "arc.agent.cache.misses"
        private const val METRIC_CIRCUIT_BREAKER_TRANSITIONS = "arc.agent.circuitbreaker.transitions"
        private const val METRIC_FALLBACK_ATTEMPTS = "arc.agent.fallback.attempts"
        private const val METRIC_TOKENS_TOTAL = "arc.agent.tokens.total"
        private const val METRIC_STREAMING_EXECUTIONS = "arc.agent.streaming.executions"
        private const val METRIC_OUTPUT_GUARD_ACTIONS = "arc.agent.output.guard.actions"
        private const val METRIC_BOUNDARY_VIOLATIONS = "arc.agent.boundary.violations"
        private const val METRIC_UNVERIFIED_RESPONSES = "arc.agent.responses.unverified"
        private const val METRIC_STAGE_DURATION = "arc.agent.stage.duration"
        private const val METRIC_LLM_LATENCY = "arc.agent.llm.latency"
        private const val METRIC_TOOL_OUTPUT_SIZE = "arc.agent.tool.output.size"
        private const val METRIC_TOOL_OUTPUT_TRUNCATED = "arc.agent.tool.output.truncated"
        private const val METRIC_TOOL_RESULT_CACHE_HITS = "arc.agent.tool.result.cache.hits"
        private const val METRIC_TOOL_RESULT_CACHE_MISSES = "arc.agent.tool.result.cache.misses"
        private const val METRIC_RAG_RETRIEVALS = "arc.agent.rag.retrievals"
        private const val METRIC_RAG_RETRIEVAL_DURATION = "arc.agent.rag.retrieval.duration"
        private const val METRIC_ACTIVE_REQUESTS = "arc.agent.active_requests"
        private const val METRIC_REQUEST_COST = "arc.agent.request.cost"
        private const val METRIC_COST_TOTAL = "arc.agent.cost.total.usd"
    }
}
