package com.arc.reactor.guard.blockrate

import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedDeque

private val logger = KotlinLogging.logger {}

/**
 * Guard 차단률 모니터 인터페이스.
 *
 * 슬라이딩 윈도우 기반으로 Guard 차단률을 추적하고,
 * 차단률이 기준선에서 비정상적으로 벗어나면 [GuardBlockRateAnomaly]를 반환한다.
 *
 * - 급증(SPIKE): 공격 가능성 — 차단률이 기준선의 [spikeMultiplier]배 초과
 * - 급감(DROP): Guard 고장 가능성 — 차단률이 기준선의 1/[dropDivisor] 미만
 *
 * @see DefaultGuardBlockRateMonitor 기본 구현체
 */
interface GuardBlockRateMonitor {

    /**
     * Guard 판정 결과를 기록한다.
     *
     * @param blocked Guard가 요청을 차단했는지 여부
     */
    fun recordGuardResult(blocked: Boolean)

    /**
     * 현재 차단률이 기준선에서 비정상적으로 벗어났는지 평가한다.
     *
     * 최소 샘플 수 미만이면 빈 리스트를 반환한다.
     *
     * @return 이상 목록 (이상 없으면 빈 리스트)
     */
    fun evaluate(): List<GuardBlockRateAnomaly>

    /**
     * 현재 슬라이딩 윈도우의 차단률을 반환한다.
     *
     * 기록된 결과가 없으면 0.0을 반환한다.
     *
     * @return 차단률 (0.0~1.0)
     */
    fun getBlockRate(): Double

    /**
     * 현재 슬라이딩 윈도우의 통계를 반환한다.
     *
     * @return 차단률 통계
     */
    fun getStats(): GuardBlockRateStats
}

/**
 * 기본 [GuardBlockRateMonitor] 구현체.
 *
 * 고정 크기 슬라이딩 윈도우에서 Guard 차단률의 기준선과 현재 값을 비교한다.
 * 윈도우 전반부를 기준선으로, 후반부를 현재 차단률로 사용한다.
 *
 * @param windowSize 슬라이딩 윈도우 크기 (기본 200)
 * @param spikeMultiplier 급증 판단 배수 (기본 3.0x) — 현재 > 기준선 × 이 값이면 SPIKE
 * @param dropDivisor 급감 판단 제수 (기본 3.0) — 현재 < 기준선 / 이 값이면 DROP
 * @param minSamples 평가에 필요한 최소 샘플 수 (기본 50)
 */
class DefaultGuardBlockRateMonitor(
    private val windowSize: Int = 200,
    private val spikeMultiplier: Double = 3.0,
    private val dropDivisor: Double = 3.0,
    private val minSamples: Int = 50
) : GuardBlockRateMonitor {

    init {
        require(windowSize > 0) { "windowSize는 양수여야 한다: $windowSize" }
        require(spikeMultiplier > 0) {
            "spikeMultiplier는 양수여야 한다: $spikeMultiplier"
        }
        require(dropDivisor > 0) {
            "dropDivisor는 양수여야 한다: $dropDivisor"
        }
        require(minSamples > 0) { "minSamples는 양수여야 한다: $minSamples" }
    }

    /** true = 차단, false = 허용 */
    private val results = ConcurrentLinkedDeque<Boolean>()

    override fun recordGuardResult(blocked: Boolean) {
        results.addLast(blocked)
        evictOverflow()
    }

    override fun evaluate(): List<GuardBlockRateAnomaly> {
        val snapshot = results.toList()
        if (snapshot.size < minSamples) return emptyList()

        val half = snapshot.size / 2
        val baseline = snapshot.subList(0, half)
        val current = snapshot.subList(half, snapshot.size)

        val baselineRate = blockRateOf(baseline)
        val currentRate = blockRateOf(current)

        val anomalies = mutableListOf<GuardBlockRateAnomaly>()
        detectSpike(currentRate, baselineRate)?.let { anomalies.add(it) }
        detectDrop(currentRate, baselineRate)?.let { anomalies.add(it) }
        return anomalies
    }

    override fun getBlockRate(): Double {
        val snapshot = results.toList()
        return if (snapshot.isEmpty()) 0.0 else blockRateOf(snapshot)
    }

    override fun getStats(): GuardBlockRateStats {
        val snapshot = results.toList()
        val blocked = snapshot.count { it }
        val half = snapshot.size / 2
        val baselineRate = if (snapshot.size >= minSamples) {
            blockRateOf(snapshot.subList(0, half))
        } else {
            blockRateOf(snapshot)
        }
        return GuardBlockRateStats(
            blockRate = if (snapshot.isEmpty()) 0.0 else blockRateOf(snapshot),
            baselineRate = baselineRate,
            totalRequests = snapshot.size,
            blockedRequests = blocked
        )
    }

    /** 급증(SPIKE) 감지: 현재 차단률 > 기준선 × spikeMultiplier */
    private fun detectSpike(
        currentRate: Double,
        baselineRate: Double
    ): GuardBlockRateAnomaly? {
        val threshold = baselineRate * spikeMultiplier
        if (currentRate <= threshold) return null

        val msg = "SPIKE: Guard 차단률 급증 — " +
            "현재 ${"%.1f".format(currentRate * 100)}%, " +
            "기준선 ${"%.1f".format(baselineRate * 100)}% " +
            "(임계값 ${"%.1f".format(threshold * 100)}%)"
        logger.debug { "Guard 차단률 이상: $msg" }

        return GuardBlockRateAnomaly(
            type = GuardAnomalyType.SPIKE,
            currentRate = currentRate,
            baselineRate = baselineRate,
            message = msg
        )
    }

    /** 급감(DROP) 감지: 기준선 > 0이고 현재 차단률 < 기준선 / dropDivisor */
    private fun detectDrop(
        currentRate: Double,
        baselineRate: Double
    ): GuardBlockRateAnomaly? {
        if (baselineRate <= 0.0) return null
        val threshold = baselineRate / dropDivisor
        if (currentRate >= threshold) return null

        val msg = "DROP: Guard 차단률 급감 — " +
            "현재 ${"%.1f".format(currentRate * 100)}%, " +
            "기준선 ${"%.1f".format(baselineRate * 100)}% " +
            "(임계값 ${"%.1f".format(threshold * 100)}%)"
        logger.debug { "Guard 차단률 이상: $msg" }

        return GuardBlockRateAnomaly(
            type = GuardAnomalyType.DROP,
            currentRate = currentRate,
            baselineRate = baselineRate,
            message = msg
        )
    }

    /** 윈도우 크기를 초과한 오래된 샘플을 제거한다. */
    private fun evictOverflow() {
        while (results.size > windowSize) {
            results.pollFirst()
        }
    }

    companion object {
        /** 차단률을 계산한다. 빈 리스트면 0.0을 반환한다. */
        internal fun blockRateOf(values: List<Boolean>): Double {
            if (values.isEmpty()) return 0.0
            return values.count { it }.toDouble() / values.size
        }
    }
}
