package com.arc.reactor.slack

import com.arc.reactor.slack.metrics.MicrometerSlackMetricsRecorder
import io.kotest.matchers.doubles.shouldBeExactly
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

class SlackMetricsRecorderTest {

    @Test
    fun `records inbound and duplicate counters`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerSlackMetricsRecorder(registry)

        recorder.recordInbound("events_api", "app_mention")
        recorder.recordDuplicate("app_mention")

        registry.get("arc.slack.inbound.total")
            .tag("entrypoint", "events_api")
            .tag("event_type", "app_mention")
            .counter().count() shouldBeExactly 1.0

        registry.get("arc.slack.duplicate.total")
            .tag("event_type", "app_mention")
            .counter().count() shouldBeExactly 1.0
    }
}
