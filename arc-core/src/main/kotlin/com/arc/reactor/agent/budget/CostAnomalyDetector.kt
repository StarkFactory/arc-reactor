package com.arc.reactor.agent.budget

import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

private val logger = KotlinLogging.logger {}

/**
 * 비용 이상 탐지기 인터페이스.
 *
 * 슬라이딩 윈도우 기반 이동 평균으로 요청당 비용 기준선을 유지하고,
 * 최신 비용이 기준선의 설정된 배수를 초과하면 [CostAnomaly]를 반환한다.
 *
 * @see DefaultCostAnomalyDetector 기본 구현체
 */
interface CostAnomalyDetector {

    /**
     * 요청 비용을 기록한다.
     *
     * @param cost 요청 비용 (USD, 0 이상)
     */
    fun recordCost(cost: Double)

    /**
     * 가장 최근에 기록된 비용이 이상인지 평가한다.
     *
     * 최소 샘플 수 미만이면 평가하지 않고 null을 반환한다.
     *
     * @return 이상 감지 시 [CostAnomaly], 정상이면 null
     */
    fun evaluate(): CostAnomaly?

    /**
     * 현재 이동 평균 기준선 비용을 반환한다.
     *
     * 기록된 비용이 없으면 0.0을 반환한다.
     *
     * @return 이동 평균 기준선 (USD)
     */
    fun getBaselineCost(): Double
}

/**
 * 기본 [CostAnomalyDetector] 구현체.
 *
 * 고정 크기 슬라이딩 윈도우의 이동 평균으로 비용 기준선을 계산하고,
 * 최신 비용이 기준선 × [thresholdMultiplier]를 초과하면 [CostAnomaly]를 반환한다.
 *
 * @param windowSize 슬라이딩 윈도우 크기 (기본 100)
 * @param thresholdMultiplier 이상 판단 배수 (기본 3.0x)
 * @param minSamples 평가에 필요한 최소 샘플 수 (기본 10)
 */
class DefaultCostAnomalyDetector(
    private val windowSize: Int = 100,
    private val thresholdMultiplier: Double = 3.0,
    private val minSamples: Int = 10
) : CostAnomalyDetector {

    init {
        require(windowSize > 0) { "windowSize는 양수여야 한다: $windowSize" }
        require(thresholdMultiplier > 0) { "thresholdMultiplier는 양수여야 한다: $thresholdMultiplier" }
        require(minSamples > 0) { "minSamples는 양수여야 한다: $minSamples" }
    }

    private val costs = ConcurrentLinkedDeque<Double>()

    override fun recordCost(cost: Double) {
        if (cost < 0) {
            logger.debug { "음수 비용 무시: $cost" }
            return
        }
        costs.addLast(cost)
        evictOverflow()
    }

    override fun evaluate(): CostAnomaly? {
        val snapshot = costs.toList()
        if (snapshot.size < minSamples) return null

        val latest = snapshot.last()
        val baseline = snapshot.average()
        if (baseline <= 0.0) return null

        val actualMultiplier = latest / baseline
        if (actualMultiplier <= thresholdMultiplier) return null

        val msg = "요청 비용 ${"%.6f".format(latest)} USD가 " +
            "기준선 ${"%.6f".format(baseline)} USD의 " +
            "${"%.1f".format(actualMultiplier)}배 — " +
            "임계값 ${"%.1f".format(thresholdMultiplier)}배 초과"
        logger.debug { "비용 이상 감지: $msg" }

        return CostAnomaly(
            currentCost = latest,
            baselineCost = baseline,
            multiplier = actualMultiplier,
            threshold = thresholdMultiplier,
            message = msg,
            timestamp = Instant.now()
        )
    }

    override fun getBaselineCost(): Double {
        val snapshot = costs.toList()
        return if (snapshot.isEmpty()) 0.0 else snapshot.average()
    }

    /** 윈도우 크기를 초과한 오래된 샘플을 제거한다. */
    private fun evictOverflow() {
        while (costs.size > windowSize) {
            costs.pollFirst()
        }
    }
}
