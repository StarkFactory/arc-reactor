package com.arc.reactor.slack

import com.arc.reactor.slack.metrics.MicrometerSlackMetricsRecorder
import io.kotest.matchers.doubles.shouldBeExactly
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

/**
 * [MicrometerSlackMetricsRecorder]의 메트릭 기록 테스트.
 *
 * Micrometer 카운터(inbound, duplicate)가 올바른 태그와 함께 기록되는지 검증한다.
 */
class SlackMetricsRecorderTest {

    @Test
    fun `inbound and duplicate counters를 기록한다`() {
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
