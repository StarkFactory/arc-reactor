package com.arc.reactor.agent.drift

import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.abs
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

/**
 * 프롬프트 드리프트 감지기 인터페이스.
 *
 * 입력/출력 길이 분포의 슬라이딩 윈도우를 유지하고,
 * 현재 평균이 기준선에서 설정된 표준편차 배수만큼 벗어나면
 * [DriftAnomaly]를 반환한다.
 *
 * @see DefaultPromptDriftDetector 기본 구현체
 */
interface PromptDriftDetector {

    /**
     * 입력 문자열 길이를 기록한다.
     *
     * @param length 입력 문자 수 (0 이상)
     */
    fun recordInput(length: Int)

    /**
     * 출력 문자열 길이를 기록한다.
     *
     * @param length 출력 문자 수 (0 이상)
     */
    fun recordOutput(length: Int)

    /**
     * 현재 분포가 기준선에서 벗어났는지 평가한다.
     *
     * 최소 샘플 수 미만이면 빈 리스트를 반환한다.
     *
     * @return 드리프트 이상 목록 (이상 없으면 빈 리스트)
     */
    fun evaluate(): List<DriftAnomaly>

    /**
     * 현재 슬라이딩 윈도우의 통계를 반환한다.
     *
     * @return 입출력 길이 분포 통계
     */
    fun getStats(): DriftStats
}

/**
 * 기본 [PromptDriftDetector] 구현체.
 *
 * 고정 크기 슬라이딩 윈도우에서 입력/출력 길이의 이동 평균과
 * 표준편차를 추적한다. 윈도우 전반부를 기준선으로, 후반부를
 * 현재 분포로 사용하여 드리프트를 감지한다.
 *
 * @param windowSize 슬라이딩 윈도우 크기 (기본 200)
 * @param deviationThreshold 이상 판단 표준편차 배수 (기본 2.0)
 * @param minSamples 평가에 필요한 최소 샘플 수 (기본 20)
 */
class DefaultPromptDriftDetector(
    private val windowSize: Int = 200,
    private val deviationThreshold: Double = 2.0,
    private val minSamples: Int = 20
) : PromptDriftDetector {

    init {
        require(windowSize > 0) { "windowSize는 양수여야 한다: $windowSize" }
        require(deviationThreshold > 0) {
            "deviationThreshold는 양수여야 한다: $deviationThreshold"
        }
        require(minSamples > 0) { "minSamples는 양수여야 한다: $minSamples" }
    }

    private val inputLengths = ConcurrentLinkedDeque<Int>()
    private val outputLengths = ConcurrentLinkedDeque<Int>()

    override fun recordInput(length: Int) {
        if (length < 0) {
            logger.debug { "음수 입력 길이 무시: $length" }
            return
        }
        inputLengths.addLast(length)
        evictOverflow(inputLengths)
    }

    override fun recordOutput(length: Int) {
        if (length < 0) {
            logger.debug { "음수 출력 길이 무시: $length" }
            return
        }
        outputLengths.addLast(length)
        evictOverflow(outputLengths)
    }

    override fun evaluate(): List<DriftAnomaly> {
        val inputSnapshot = inputLengths.toList()
        val outputSnapshot = outputLengths.toList()
        val anomalies = mutableListOf<DriftAnomaly>()
        evaluateDistribution(inputSnapshot, DriftType.INPUT_LENGTH)
            ?.let { anomalies.add(it) }
        evaluateDistribution(outputSnapshot, DriftType.OUTPUT_LENGTH)
            ?.let { anomalies.add(it) }
        return anomalies
    }

    override fun getStats(): DriftStats {
        val inputs = inputLengths.toList().map { it.toDouble() }
        val outputs = outputLengths.toList().map { it.toDouble() }
        return DriftStats(
            inputMean = meanOf(inputs),
            inputStdDev = stdDevOf(inputs),
            outputMean = meanOf(outputs),
            outputStdDev = stdDevOf(outputs),
            sampleCount = inputs.size
        )
    }

    /**
     * 단일 분포의 드리프트를 평가한다.
     *
     * 윈도우 전반부를 기준선, 후반부를 현재로 분할하여
     * 현재 평균이 기준선 평균에서 [deviationThreshold] 표준편차 이상
     * 벗어나면 [DriftAnomaly]를 반환한다.
     */
    private fun evaluateDistribution(
        snapshot: List<Int>,
        type: DriftType
    ): DriftAnomaly? {
        if (snapshot.size < minSamples) return null

        val half = snapshot.size / 2
        val baseline = snapshot.subList(0, half).map { it.toDouble() }
        val current = snapshot.subList(half, snapshot.size).map { it.toDouble() }

        val baselineMean = meanOf(baseline)
        val rawStdDev = stdDevOf(baseline)
        val currentMean = meanOf(current)

        // 기준선이 완전히 균일할 때(stdDev=0): 평균 차이가 있으면 즉시 드리프트
        if (rawStdDev <= 0.0) {
            if (currentMean == baselineMean) return null
        }

        // stdDev=0이면 최소 바닥값(기준선 평균의 1%) 사용
        val effectiveStdDev = if (rawStdDev <= 0.0) {
            (baselineMean * MIN_STDDEV_FLOOR_RATIO).coerceAtLeast(1.0)
        } else rawStdDev

        val factor = abs(currentMean - baselineMean) / effectiveStdDev
        if (factor <= deviationThreshold) return null

        val label = if (type == DriftType.INPUT_LENGTH) "입력" else "출력"
        val msg = "${label} 길이 드리프트 감지: " +
            "현재 평균 ${"%.1f".format(currentMean)}, " +
            "기준선 평균 ${"%.1f".format(baselineMean)}, " +
            "편차 ${"%.1f".format(factor)}σ " +
            "(임계값 ${"%.1f".format(deviationThreshold)}σ)"
        logger.debug { "프롬프트 드리프트: $msg" }

        return DriftAnomaly(
            type = type,
            currentMean = currentMean,
            baselineMean = baselineMean,
            standardDeviation = effectiveStdDev,
            deviationFactor = factor,
            message = msg
        )
    }

    /** 윈도우 크기를 초과한 오래된 샘플을 제거한다. */
    private fun evictOverflow(deque: ConcurrentLinkedDeque<Int>) {
        while (deque.size > windowSize) {
            deque.pollFirst()
        }
    }

    companion object {
        /**
         * 기준선 표준편차가 0일 때 사용하는 최소 바닥값 비율.
         * 기준선 평균의 1%를 최소 표준편차로 사용한다 (최소 1.0).
         */
        private const val MIN_STDDEV_FLOOR_RATIO = 0.01

        /** 평균을 계산한다. 빈 리스트면 0.0을 반환한다. */
        internal fun meanOf(values: List<Double>): Double {
            return if (values.isEmpty()) 0.0 else values.average()
        }

        /** 모표준편차를 계산한다. 빈 리스트면 0.0을 반환한다. */
        internal fun stdDevOf(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            return sqrt(variance)
        }
    }
}
