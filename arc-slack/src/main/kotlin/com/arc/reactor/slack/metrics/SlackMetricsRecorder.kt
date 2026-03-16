package com.arc.reactor.slack.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * Slack 게이트웨이 운영 메트릭 기록 인터페이스.
 *
 * 인바운드 이벤트, 중복, 드롭, 핸들러 처리 시간, API 호출, 재시도,
 * response_url 전송 등의 메트릭을 기록한다.
 *
 * @see MicrometerSlackMetricsRecorder
 * @see NoOpSlackMetricsRecorder
 */
interface SlackMetricsRecorder {
    fun recordInbound(entrypoint: String, eventType: String = "n/a")
    fun recordDuplicate(eventType: String)
    fun recordDropped(entrypoint: String, reason: String, eventType: String = "n/a")
    fun recordHandler(entrypoint: String, eventType: String, success: Boolean, durationMs: Long)
    fun recordApiCall(method: String, outcome: String, durationMs: Long)
    fun recordApiRetry(method: String, reason: String)
    fun recordResponseUrl(outcome: String)
}

/** 아무 동작도 수행하지 않는 No-op 메트릭 기록기. Micrometer가 없을 때 사용된다. */
class NoOpSlackMetricsRecorder : SlackMetricsRecorder {
    override fun recordInbound(entrypoint: String, eventType: String) {}
    override fun recordDuplicate(eventType: String) {}
    override fun recordDropped(entrypoint: String, reason: String, eventType: String) {}
    override fun recordHandler(entrypoint: String, eventType: String, success: Boolean, durationMs: Long) {}
    override fun recordApiCall(method: String, outcome: String, durationMs: Long) {}
    override fun recordApiRetry(method: String, reason: String) {}
    override fun recordResponseUrl(outcome: String) {}
}

/** Micrometer 기반 Slack 메트릭 기록기. 카운터와 타이머를 사용하여 메트릭을 기록한다. */
class MicrometerSlackMetricsRecorder(
    private val registry: MeterRegistry
) : SlackMetricsRecorder {
    override fun recordInbound(entrypoint: String, eventType: String) {
        registry.counter(
            "arc.slack.inbound.total",
            "entrypoint", entrypoint,
            "event_type", eventType
        ).increment()
    }

    override fun recordDuplicate(eventType: String) {
        registry.counter(
            "arc.slack.duplicate.total",
            "event_type", eventType
        ).increment()
    }

    override fun recordDropped(entrypoint: String, reason: String, eventType: String) {
        registry.counter(
            "arc.slack.dropped.total",
            "entrypoint", entrypoint,
            "reason", reason,
            "event_type", eventType
        ).increment()
    }

    override fun recordHandler(entrypoint: String, eventType: String, success: Boolean, durationMs: Long) {
        Timer.builder("arc.slack.handler.duration")
            .tag("entrypoint", entrypoint)
            .tag("event_type", eventType)
            .tag("success", success.toString())
            .publishPercentileHistogram()
            .register(registry)
            .record(Duration.ofMillis(durationMs.coerceAtLeast(0)))
    }

    override fun recordApiCall(method: String, outcome: String, durationMs: Long) {
        Timer.builder("arc.slack.api.duration")
            .tag("method", method)
            .tag("outcome", outcome)
            .publishPercentileHistogram()
            .register(registry)
            .record(Duration.ofMillis(durationMs.coerceAtLeast(0)))
    }

    override fun recordApiRetry(method: String, reason: String) {
        registry.counter(
            "arc.slack.api.retry.total",
            "method", method,
            "reason", reason
        ).increment()
    }

    override fun recordResponseUrl(outcome: String) {
        registry.counter(
            "arc.slack.response_url.total",
            "outcome", outcome
        ).increment()
    }
}
