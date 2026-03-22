package com.arc.reactor.agent.slo

import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

private val logger = KotlinLogging.logger {}

/**
 * 기본 [SloAlertEvaluator] 구현체.
 *
 * 시간 기반 슬라이딩 윈도우로 레이턴시와 에러율을 추적한다.
 * P95 레이턴시 또는 에러율이 임계값을 초과하면 [SloViolation]을 반환한다.
 * 쿨다운 기간 내에는 동일 유형의 알림을 반복하지 않는다.
 *
 * @param latencyThresholdMs P95 레이턴시 임계값 (밀리초)
 * @param errorRateThreshold 에러율 임계값 (0.0~1.0)
 * @param windowSeconds 평가 윈도우 크기 (초)
 * @param cooldownSeconds 동일 유형 알림 재발송 방지 기간 (초)
 * @param nowMs 현재 시간 공급자 (테스트용)
 */
class DefaultSloAlertEvaluator(
    private val latencyThresholdMs: Long,
    private val errorRateThreshold: Double,
    private val windowSeconds: Long,
    private val cooldownSeconds: Long,
    private val nowMs: () -> Long = System::currentTimeMillis
) : SloAlertEvaluator {

    /** 타임스탬프 포함 레이턴시 샘플 */
    private data class TimestampedLatency(val timestampMs: Long, val durationMs: Long)

    /** 타임스탬프 포함 결과 샘플 */
    private data class TimestampedResult(val timestampMs: Long, val success: Boolean)

    private val latencies = ConcurrentLinkedDeque<TimestampedLatency>()
    private val results = ConcurrentLinkedDeque<TimestampedResult>()

    /** 마지막 알림 발송 시각 (유형별) */
    @Volatile private var lastLatencyAlertMs: Long = 0L
    @Volatile private var lastErrorRateAlertMs: Long = 0L

    override fun recordLatency(durationMs: Long) {
        latencies.addLast(TimestampedLatency(nowMs(), durationMs.coerceAtLeast(0)))
        evictExpired()
    }

    override fun recordResult(success: Boolean) {
        results.addLast(TimestampedResult(nowMs(), success))
        evictExpired()
    }

    override fun evaluate(): List<SloViolation> {
        evictExpired()
        val now = nowMs()
        val violations = mutableListOf<SloViolation>()

        evaluateLatency(now)?.let { violations.add(it) }
        evaluateErrorRate(now)?.let { violations.add(it) }

        return violations
    }

    /** P95 레이턴시 평가. 쿨다운 내이면 null 반환. */
    private fun evaluateLatency(now: Long): SloViolation? {
        if (isCoolingDown(lastLatencyAlertMs, now)) return null
        val snapshot = latencies.map { it.durationMs }
        if (snapshot.size < MIN_SAMPLES) return null

        val p95 = percentile(snapshot, 95)
        if (p95 <= latencyThresholdMs) return null

        lastLatencyAlertMs = now
        logger.debug { "SLO 레이턴시 위반 감지: P95=${p95}ms > ${latencyThresholdMs}ms" }
        return SloViolation(
            type = SloViolationType.LATENCY,
            currentValue = p95.toDouble(),
            threshold = latencyThresholdMs.toDouble(),
            message = "P95 레이턴시 ${p95}ms가 임계값 ${latencyThresholdMs}ms를 초과했습니다",
            timestamp = Instant.ofEpochMilli(now)
        )
    }

    /** 에러율 평가. 쿨다운 내이면 null 반환. */
    private fun evaluateErrorRate(now: Long): SloViolation? {
        if (isCoolingDown(lastErrorRateAlertMs, now)) return null
        val snapshot = results.toList()
        if (snapshot.size < MIN_SAMPLES) return null

        val errorCount = snapshot.count { !it.success }
        val errorRate = errorCount.toDouble() / snapshot.size
        if (errorRate <= errorRateThreshold) return null

        lastErrorRateAlertMs = now
        val pct = String.format("%.1f", errorRate * 100)
        val thresholdPct = String.format("%.1f", errorRateThreshold * 100)
        logger.debug { "SLO 에러율 위반 감지: ${pct}% > ${thresholdPct}%" }
        return SloViolation(
            type = SloViolationType.ERROR_RATE,
            currentValue = errorRate,
            threshold = errorRateThreshold,
            message = "에러율 ${pct}%가 임계값 ${thresholdPct}%를 초과했습니다",
            timestamp = Instant.ofEpochMilli(now)
        )
    }

    /** 쿨다운 기간 내인지 확인한다. */
    private fun isCoolingDown(lastAlertMs: Long, now: Long): Boolean {
        return lastAlertMs > 0 && (now - lastAlertMs) < cooldownSeconds * 1000
    }

    /** 윈도우 밖의 만료된 샘플을 제거한다. */
    private fun evictExpired() {
        val cutoff = nowMs() - windowSeconds * 1000
        while (true) {
            val head = latencies.peekFirst() ?: break
            if (head.timestampMs < cutoff) latencies.pollFirst() else break
        }
        while (true) {
            val head = results.peekFirst() ?: break
            if (head.timestampMs < cutoff) results.pollFirst() else break
        }
    }

    /** 정렬 기반 백분위수 계산. */
    private fun percentile(values: List<Long>, pct: Int): Long {
        val sorted = values.sorted()
        val index = ((pct / 100.0) * sorted.size).toInt()
            .coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    companion object {
        /** 평가에 필요한 최소 샘플 수 */
        private const val MIN_SAMPLES = 5
    }
}
