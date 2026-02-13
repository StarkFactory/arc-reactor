package com.arc.reactor.slack.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * Records operational metrics for Slack gateway processing.
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

class NoOpSlackMetricsRecorder : SlackMetricsRecorder {
    override fun recordInbound(entrypoint: String, eventType: String) {}
    override fun recordDuplicate(eventType: String) {}
    override fun recordDropped(entrypoint: String, reason: String, eventType: String) {}
    override fun recordHandler(entrypoint: String, eventType: String, success: Boolean, durationMs: Long) {}
    override fun recordApiCall(method: String, outcome: String, durationMs: Long) {}
    override fun recordApiRetry(method: String, reason: String) {}
    override fun recordResponseUrl(outcome: String) {}
}

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
