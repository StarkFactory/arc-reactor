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

@DisplayName("ReAct 수렴 추적")
class ReActConvergenceTrackingTest {

    private lateinit var registry: MeterRegistry
    private lateinit var slaMetrics: MicrometerSlaMetrics

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        slaMetrics = MicrometerSlaMetrics(registry)
    }

    @Nested
    @DisplayName("종료 사유별 수렴 기록")
    inner class StopReasonTracking {

        @Test
        fun `completed 종료 사유를 기록한다`() {
            slaMetrics.recordReActConvergence(3, "completed", 1500)

            val timer = registry.find("arc.sla.react.convergence")
                .tag("stop_reason", "completed")
                .tag("step_bucket", "2-3")
                .timer()
            timer.shouldNotBeNull()
            timer.count() shouldBe 1L
            timer.totalTime(TimeUnit.MILLISECONDS) shouldBe 1500.0
        }

        @Test
        fun `max_tool_calls 종료 사유를 기록한다`() {
            slaMetrics.recordReActConvergence(10, "max_tool_calls", 30000)

            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("stop_reason", "max_tool_calls")
                .tag("step_bucket", "6-10")
                .counter()
            counter.shouldNotBeNull()
            counter.count() shouldBe 1.0
        }

        @Test
        fun `budget_exhausted 종료 사유를 기록한다`() {
            slaMetrics.recordReActConvergence(7, "budget_exhausted", 20000)

            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("stop_reason", "budget_exhausted")
                .counter()
            counter.shouldNotBeNull()
            counter.count() shouldBe 1.0
        }

        @Test
        fun `timeout 종료 사유를 기록한다`() {
            slaMetrics.recordReActConvergence(4, "timeout", 30000)

            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("stop_reason", "timeout")
                .tag("step_bucket", "4-5")
                .counter()
            counter.shouldNotBeNull()
            counter.count() shouldBe 1.0
        }

        @Test
        fun `error 종료 사유를 기록한다`() {
            slaMetrics.recordReActConvergence(1, "error", 500)

            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("stop_reason", "error")
                .tag("step_bucket", "1")
                .counter()
            counter.shouldNotBeNull()
            counter.count() shouldBe 1.0
        }
    }

    @Nested
    @DisplayName("스텝 버킷 분류")
    inner class StepBuckets {

        @Test
        fun `1스텝은 버킷 1로 분류된다`() {
            slaMetrics.recordReActConvergence(1, "completed", 100)

            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("step_bucket", "1")
                .counter()
            counter.shouldNotBeNull()
            counter.count() shouldBe 1.0
        }

        @Test
        fun `2-3스텝은 버킷 2-3으로 분류된다`() {
            slaMetrics.recordReActConvergence(2, "completed", 200)
            slaMetrics.recordReActConvergence(3, "completed", 300)

            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("step_bucket", "2-3")
                .counter()
            counter.shouldNotBeNull()
            counter.count() shouldBe 2.0
        }

        @Test
        fun `4-5스텝은 버킷 4-5로 분류된다`() {
            slaMetrics.recordReActConvergence(5, "completed", 500)

            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("step_bucket", "4-5")
                .counter()
            counter.shouldNotBeNull()
            counter.count() shouldBe 1.0
        }

        @Test
        fun `6-10스텝은 버킷 6-10으로 분류된다`() {
            slaMetrics.recordReActConvergence(6, "completed", 600)
            slaMetrics.recordReActConvergence(10, "completed", 1000)

            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("step_bucket", "6-10")
                .counter()
            counter.shouldNotBeNull()
            counter.count() shouldBe 2.0
        }

        @Test
        fun `10초과 스텝은 버킷 10+로 분류된다`() {
            slaMetrics.recordReActConvergence(15, "max_tool_calls", 15000)

            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("step_bucket", "10+")
                .counter()
            counter.shouldNotBeNull()
            counter.count() shouldBe 1.0
        }
    }

    @Nested
    @DisplayName("메타데이터 전달")
    inner class MetadataHandling {

        @Test
        fun `메타데이터 없이 호출할 수 있다`() {
            slaMetrics.recordReActConvergence(2, "completed", 1000)

            val timer = registry.find("arc.sla.react.convergence")
                .tag("stop_reason", "completed")
                .timer()
            timer.shouldNotBeNull()
            timer.count() shouldBe 1L
        }

        @Test
        fun `메타데이터와 함께 호출할 수 있다`() {
            slaMetrics.recordReActConvergence(
                steps = 5,
                stopReason = "completed",
                durationMs = 2000,
                metadata = mapOf("agentId" to "test-agent", "tenantId" to "tenant-1")
            )

            val timer = registry.find("arc.sla.react.convergence")
                .tag("stop_reason", "completed")
                .timer()
            timer.shouldNotBeNull()
            timer.count() shouldBe 1L
        }
    }

    @Nested
    @DisplayName("누적 카운터")
    inner class CumulativeCounters {

        @Test
        fun `동일 종료 사유가 누적된다`() {
            repeat(5) {
                slaMetrics.recordReActConvergence(3, "completed", 1000L + it * 100)
            }

            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("stop_reason", "completed")
                .counter()
            counter.shouldNotBeNull()
            counter.count() shouldBe 5.0

            val timer = registry.find("arc.sla.react.convergence")
                .tag("stop_reason", "completed")
                .timer()
            timer.shouldNotBeNull()
            timer.count() shouldBe 5L
        }

        @Test
        fun `음수 소요 시간은 0으로 보정된다`() {
            slaMetrics.recordReActConvergence(1, "error", -100)

            val timer = registry.find("arc.sla.react.convergence")
                .tag("stop_reason", "error")
                .timer()
            timer.shouldNotBeNull()
            timer.totalTime(TimeUnit.MILLISECONDS) shouldBe 0.0
        }
    }
}
