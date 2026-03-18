package com.arc.reactor.agent.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Micrometer 기반 [SlaMetrics] 구현체.
 *
 * E2E 레이턴시(P50/P95/P99), ReAct 루프 수렴, 도구 실패 분류, 시스템 가용성을
 * [MeterRegistry]에 기록한다.
 *
 * 가용성은 최근 [AVAILABILITY_WINDOW_SIZE]개 샘플의 롤링 윈도우로 계산한다.
 *
 * @param registry Micrometer 메트릭 레지스트리
 */
class MicrometerSlaMetrics(
    private val registry: MeterRegistry
) : SlaMetrics {

    /** 가용성 롤링 윈도우: true=정상, false=비정상 */
    private val availabilitySamples = ConcurrentLinkedDeque<Boolean>()
    private val healthySampleCount = AtomicLong(0)
    private val totalSampleCount = AtomicLong(0)

    init {
        registry.gauge(METRIC_AVAILABILITY, this) { it.availabilityRatio() }
    }

    override fun recordReActConvergence(
        steps: Int,
        stopReason: String,
        durationMs: Long,
        metadata: Map<String, Any>
    ) {
        val bucket = stepBucket(steps)
        Timer.builder(METRIC_REACT_CONVERGENCE)
            .tag("stop_reason", stopReason)
            .tag("step_bucket", bucket)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)
            .record(durationMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)

        Counter.builder(METRIC_REACT_CONVERGENCE_TOTAL)
            .tag("stop_reason", stopReason)
            .tag("step_bucket", bucket)
            .register(registry)
            .increment()
    }

    override fun recordToolFailureDetail(
        toolName: String,
        errorType: String,
        errorMessage: String,
        durationMs: Long
    ) {
        Counter.builder(METRIC_TOOL_FAILURE)
            .tag("tool", toolName)
            .tag("error_type", errorType)
            .register(registry)
            .increment()

        Timer.builder(METRIC_TOOL_FAILURE_DURATION)
            .tag("tool", toolName)
            .tag("error_type", errorType)
            .register(registry)
            .record(durationMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    override fun recordAvailabilitySample(healthy: Boolean) {
        availabilitySamples.addLast(healthy)
        totalSampleCount.incrementAndGet()
        if (healthy) healthySampleCount.incrementAndGet()

        // 롤링 윈도우 초과 시 가장 오래된 샘플 제거
        while (availabilitySamples.size > AVAILABILITY_WINDOW_SIZE) {
            val removed = availabilitySamples.pollFirst() ?: break
            totalSampleCount.decrementAndGet()
            if (removed) healthySampleCount.decrementAndGet()
        }
    }

    override fun recordE2eLatency(durationMs: Long, channel: String) {
        Timer.builder(METRIC_E2E_LATENCY)
            .tag("channel", channel)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)
            .record(durationMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    /** 롤링 윈도우 기반 가용성 비율 (0.0~1.0). 샘플 없으면 1.0. */
    private fun availabilityRatio(): Double {
        val total = totalSampleCount.get()
        if (total == 0L) return 1.0
        return healthySampleCount.get().toDouble() / total
    }

    /** 스텝 수를 버킷 문자열로 변환한다. */
    private fun stepBucket(steps: Int): String = when {
        steps <= 1 -> "1"
        steps <= 3 -> "2-3"
        steps <= 5 -> "4-5"
        steps <= 10 -> "6-10"
        else -> "10+"
    }

    companion object {
        private const val METRIC_REACT_CONVERGENCE = "arc.sla.react.convergence"
        private const val METRIC_REACT_CONVERGENCE_TOTAL = "arc.sla.react.convergence.total"
        private const val METRIC_TOOL_FAILURE = "arc.sla.tool.failure"
        private const val METRIC_TOOL_FAILURE_DURATION = "arc.sla.tool.failure.duration"
        private const val METRIC_AVAILABILITY = "arc.sla.availability"
        private const val METRIC_E2E_LATENCY = "arc.sla.e2e.latency"

        /** 가용성 롤링 윈도우 크기 (최근 N개 샘플) */
        private const val AVAILABILITY_WINDOW_SIZE = 100
    }
}
