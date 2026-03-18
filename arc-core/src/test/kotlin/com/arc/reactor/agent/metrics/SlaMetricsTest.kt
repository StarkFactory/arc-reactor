package com.arc.reactor.agent.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

@DisplayName("SlaMetrics")
class SlaMetricsTest {

    private lateinit var registry: MeterRegistry
    private lateinit var slaMetrics: MicrometerSlaMetrics

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        slaMetrics = MicrometerSlaMetrics(registry)
    }

    @Nested
    @DisplayName("E2E 레이턴시")
    inner class E2eLatency {

        @Test
        fun `채널별 E2E 레이턴시를 기록한다`() {
            slaMetrics.recordE2eLatency(150, "rest")
            slaMetrics.recordE2eLatency(300, "slack")

            val restTimer = registry.find("arc.sla.e2e.latency")
                .tag("channel", "rest")
                .timer()
            restTimer.shouldNotBeNull()
            restTimer.count() shouldBe 1L
            restTimer.totalTime(TimeUnit.MILLISECONDS) shouldBe 150.0

            val slackTimer = registry.find("arc.sla.e2e.latency")
                .tag("channel", "slack")
                .timer()
            slackTimer.shouldNotBeNull()
            slackTimer.count() shouldBe 1L
        }

        @Test
        fun `기본 채널은 unknown이다`() {
            slaMetrics.recordE2eLatency(100)

            val timer = registry.find("arc.sla.e2e.latency")
                .tag("channel", "unknown")
                .timer()
            timer.shouldNotBeNull()
            timer.count() shouldBe 1L
        }

        @Test
        fun `음수 소요 시간은 0으로 보정된다`() {
            slaMetrics.recordE2eLatency(-50, "rest")

            val timer = registry.find("arc.sla.e2e.latency")
                .tag("channel", "rest")
                .timer()
            timer.shouldNotBeNull()
            timer.totalTime(TimeUnit.MILLISECONDS) shouldBe 0.0
        }
    }

    @Nested
    @DisplayName("도구 실패 상세")
    inner class ToolFailureDetail {

        @Test
        fun `도구별 에러 유형 카운터를 기록한다`() {
            slaMetrics.recordToolFailureDetail("search_tool", "timeout", "Connection timed out", 5000)
            slaMetrics.recordToolFailureDetail("search_tool", "timeout", "Read timed out", 3000)
            slaMetrics.recordToolFailureDetail("db_tool", "connection", "Connection refused", 100)

            val searchTimeout = registry.find("arc.sla.tool.failure")
                .tag("tool", "search_tool")
                .tag("error_type", "timeout")
                .counter()
            searchTimeout.shouldNotBeNull()
            searchTimeout.count() shouldBe 2.0

            val dbConnection = registry.find("arc.sla.tool.failure")
                .tag("tool", "db_tool")
                .tag("error_type", "connection")
                .counter()
            dbConnection.shouldNotBeNull()
            dbConnection.count() shouldBe 1.0
        }

        @Test
        fun `도구 실패 소요 시간을 기록한다`() {
            slaMetrics.recordToolFailureDetail("mcp_tool", "mcp_unavailable", "Server down", 2000)

            val timer = registry.find("arc.sla.tool.failure.duration")
                .tag("tool", "mcp_tool")
                .tag("error_type", "mcp_unavailable")
                .timer()
            timer.shouldNotBeNull()
            timer.totalTime(TimeUnit.MILLISECONDS) shouldBe 2000.0
        }
    }

    @Nested
    @DisplayName("가용성 샘플")
    inner class Availability {

        @Test
        fun `정상 샘플만 있으면 가용성 1점0이다`() {
            repeat(10) { slaMetrics.recordAvailabilitySample(true) }

            val gauge = registry.find("arc.sla.availability").gauge()
            gauge.shouldNotBeNull()
            gauge.value() shouldBe 1.0
        }

        @Test
        fun `비정상 샘플만 있으면 가용성 0점0이다`() {
            repeat(10) { slaMetrics.recordAvailabilitySample(false) }

            val gauge = registry.find("arc.sla.availability").gauge()
            gauge.shouldNotBeNull()
            gauge.value() shouldBe 0.0
        }

        @Test
        fun `혼합 샘플은 비율로 계산한다`() {
            repeat(8) { slaMetrics.recordAvailabilitySample(true) }
            repeat(2) { slaMetrics.recordAvailabilitySample(false) }

            val gauge = registry.find("arc.sla.availability").gauge()
            gauge.shouldNotBeNull()
            gauge.value() shouldBe 0.8
        }

        @Test
        fun `롤링 윈도우를 초과하면 오래된 샘플을 제거한다`() {
            // 먼저 100개 비정상 샘플 추가 (윈도우 크기 = 100)
            repeat(100) { slaMetrics.recordAvailabilitySample(false) }

            val gaugeBefore = registry.find("arc.sla.availability").gauge()
            gaugeBefore.shouldNotBeNull()
            gaugeBefore.value() shouldBe 0.0

            // 이후 100개 정상 샘플 추가 → 비정상 샘플이 모두 밀려남
            repeat(100) { slaMetrics.recordAvailabilitySample(true) }

            val gaugeAfter = registry.find("arc.sla.availability").gauge()
            gaugeAfter.shouldNotBeNull()
            gaugeAfter.value() shouldBe 1.0
        }

        @Test
        fun `샘플이 없으면 가용성 1점0이다`() {
            val gauge = registry.find("arc.sla.availability").gauge()
            gauge.shouldNotBeNull()
            gauge.value() shouldBe 1.0
        }
    }

    @Nested
    @DisplayName("NoOp 구현체")
    inner class NoOp {

        @Test
        fun `NoOpSlaMetrics는 예외 없이 모든 메서드를 호출할 수 있다`() {
            val noOp = NoOpSlaMetrics()
            noOp.recordReActConvergence(5, "completed", 1000)
            noOp.recordReActConvergence(3, "error", 500, mapOf("key" to "value"))
            noOp.recordToolFailureDetail("tool", "timeout", "msg", 100)
            noOp.recordAvailabilitySample(true)
            noOp.recordAvailabilitySample(false)
            noOp.recordE2eLatency(200)
            noOp.recordE2eLatency(300, "slack")
        }
    }
}
