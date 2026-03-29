package com.arc.reactor.agent.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [MicrometerSlaMetrics] 유닛 테스트.
 *
 * 검증 범위:
 * - 가용성 롤링 윈도우 계산 (샘플 없음 / 정상 / 비정상 혼합 / 윈도우 초과)
 * - ReAct 수렴 타이머 + 카운터 기록 및 stepBucket 분류
 * - 도구 실패 카운터 + 타이머 기록
 * - E2E 레이턴시 타이머 기록
 * - NoOpSlaMetrics: 모든 메서드가 예외 없이 통과
 */
class MicrometerSlaMetricsTest {

    // ─────────────────────────────────────────────────────────────────────
    // 가용성 롤링 윈도우
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class AvailabilityRollingWindow {

        @Test
        fun `샘플이 없으면 가용성은 1_0이어야 한다`() {
            val registry = SimpleMeterRegistry()
            // 게이지만 등록, 아직 샘플 없음
            MicrometerSlaMetrics(registry)

            val gauge = registry.find("arc.sla.availability").gauge()
            assertEquals(
                1.0,
                gauge!!.value(),
                0.001,
                "샘플이 없는 초기 상태에서 가용성은 1.0이어야 한다"
            )
        }

        @Test
        fun `모든 샘플이 정상이면 가용성은 1_0이어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            repeat(5) { metrics.recordAvailabilitySample(healthy = true) }

            val gauge = registry.find("arc.sla.availability").gauge()
            assertEquals(
                1.0,
                gauge!!.value(),
                0.001,
                "모든 샘플이 정상이면 가용성 1.0이어야 한다"
            )
        }

        @Test
        fun `정상 비율에 따라 가용성을 계산해야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            // 정상 7개, 비정상 3개 → 70%
            repeat(7) { metrics.recordAvailabilitySample(healthy = true) }
            repeat(3) { metrics.recordAvailabilitySample(healthy = false) }

            val gauge = registry.find("arc.sla.availability").gauge()
            assertEquals(
                0.7,
                gauge!!.value(),
                0.001,
                "정상 7/전체 10 → 가용성 0.7이어야 한다"
            )
        }

        @Test
        fun `모든 샘플이 비정상이면 가용성은 0_0이어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            repeat(10) { metrics.recordAvailabilitySample(healthy = false) }

            val gauge = registry.find("arc.sla.availability").gauge()
            assertEquals(
                0.0,
                gauge!!.value(),
                0.001,
                "모든 샘플이 비정상이면 가용성 0.0이어야 한다"
            )
        }

        @Test
        fun `윈도우 초과 시 오래된 샘플이 제거되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            // 100개 정상 → 1 정상 비정상 → 초과해서 101번째부터 오래된 정상 제거
            // 101개를 넣으면 첫 번째(정상)가 제거되고 나머지 100개 중 99 정상, 1 비정상
            repeat(100) { metrics.recordAvailabilitySample(healthy = true) }
            metrics.recordAvailabilitySample(healthy = false) // 101번째, 윈도우=100 → 첫 번째(정상) 제거

            val gauge = registry.find("arc.sla.availability").gauge()
            // 윈도우: [정상 x99, 비정상 x1] → 99/100 = 0.99
            assertEquals(
                0.99,
                gauge!!.value(),
                0.001,
                "101번째 샘플 추가 후 가용성은 0.99이어야 한다 (첫 번째 정상 샘플 제거)"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ReAct 수렴 타이머 + 카운터 + stepBucket
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ReActConvergenceRecording {

        @Test
        fun `수렴 기록 시 타이머와 카운터에 등록되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordReActConvergence(
                steps = 3,
                stopReason = "completed",
                durationMs = 1500
            )

            val timer = registry.find("arc.sla.react.convergence")
                .tag("stop_reason", "completed")
                .tag("step_bucket", "2-3")
                .timer()
            val counter = registry.find("arc.sla.react.convergence.total")
                .tag("stop_reason", "completed")
                .counter()

            assertEquals(
                1,
                timer!!.count(),
                "타이머에 1개 샘플이 기록되어야 한다"
            )
            assertEquals(
                1.0,
                counter!!.count(),
                0.001,
                "카운터가 1 증가해야 한다"
            )
        }

        @Test
        fun `step 1은 step_bucket 1로 분류되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordReActConvergence(steps = 1, stopReason = "completed", durationMs = 100)

            val timer = registry.find("arc.sla.react.convergence")
                .tag("step_bucket", "1")
                .timer()
            assertTrue(
                timer != null && timer.count() == 1L,
                "step=1은 step_bucket='1'이어야 한다"
            )
        }

        @Test
        fun `step 2는 step_bucket 2-3으로 분류되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordReActConvergence(steps = 2, stopReason = "completed", durationMs = 200)

            val timer = registry.find("arc.sla.react.convergence")
                .tag("step_bucket", "2-3")
                .timer()
            assertTrue(
                timer != null && timer.count() == 1L,
                "step=2는 step_bucket='2-3'이어야 한다"
            )
        }

        @Test
        fun `step 4는 step_bucket 4-5로 분류되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordReActConvergence(steps = 4, stopReason = "budget_exhausted", durationMs = 800)

            val timer = registry.find("arc.sla.react.convergence")
                .tag("step_bucket", "4-5")
                .timer()
            assertTrue(
                timer != null && timer.count() == 1L,
                "step=4는 step_bucket='4-5'이어야 한다"
            )
        }

        @Test
        fun `step 8은 step_bucket 6-10으로 분류되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordReActConvergence(steps = 8, stopReason = "max_tool_calls", durationMs = 2000)

            val timer = registry.find("arc.sla.react.convergence")
                .tag("step_bucket", "6-10")
                .timer()
            assertTrue(
                timer != null && timer.count() == 1L,
                "step=8은 step_bucket='6-10'이어야 한다"
            )
        }

        @Test
        fun `step 11은 step_bucket 10+으로 분류되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordReActConvergence(steps = 11, stopReason = "timeout", durationMs = 5000)

            val timer = registry.find("arc.sla.react.convergence")
                .tag("step_bucket", "10+")
                .timer()
            assertTrue(
                timer != null && timer.count() == 1L,
                "step=11은 step_bucket='10+'이어야 한다"
            )
        }

        @Test
        fun `음수 durationMs는 0으로 보정되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            // 예외가 발생하지 않아야 한다
            metrics.recordReActConvergence(steps = 2, stopReason = "error", durationMs = -100)

            val timer = registry.find("arc.sla.react.convergence")
                .tag("stop_reason", "error")
                .timer()
            assertTrue(
                timer != null && timer.count() == 1L,
                "음수 durationMs도 예외 없이 기록되어야 한다"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 도구 실패 카운터 + 타이머
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolFailureRecording {

        @Test
        fun `도구 실패 기록 시 카운터와 타이머에 등록되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordToolFailureDetail(
                toolName = "jira_search",
                errorType = "timeout",
                errorMessage = "Connection timed out",
                durationMs = 3000
            )

            val counter = registry.find("arc.sla.tool.failure")
                .tag("tool", "jira_search")
                .tag("error_type", "timeout")
                .counter()
            val timer = registry.find("arc.sla.tool.failure.duration")
                .tag("tool", "jira_search")
                .tag("error_type", "timeout")
                .timer()

            assertEquals(
                1.0,
                counter!!.count(),
                0.001,
                "도구 실패 카운터가 1 증가해야 한다"
            )
            assertEquals(
                1L,
                timer!!.count(),
                "도구 실패 타이머에 1개 샘플이 기록되어야 한다"
            )
        }

        @Test
        fun `서로 다른 도구의 실패는 개별 메트릭으로 기록되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordToolFailureDetail("tool_a", "timeout", "err", 100)
            metrics.recordToolFailureDetail("tool_b", "connection", "err", 200)

            val counterA = registry.find("arc.sla.tool.failure").tag("tool", "tool_a").counter()
            val counterB = registry.find("arc.sla.tool.failure").tag("tool", "tool_b").counter()

            assertEquals(1.0, counterA!!.count(), 0.001, "tool_a 카운터는 1이어야 한다")
            assertEquals(1.0, counterB!!.count(), 0.001, "tool_b 카운터는 1이어야 한다")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // E2E 레이턴시 타이머
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class E2eLatencyRecording {

        @Test
        fun `채널별 E2E 레이턴시가 기록되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordE2eLatency(durationMs = 250, channel = "slack")

            val timer = registry.find("arc.sla.e2e.latency")
                .tag("channel", "slack")
                .timer()

            assertEquals(
                1L,
                timer!!.count(),
                "E2E 레이턴시 타이머에 1개 샘플이 기록되어야 한다"
            )
        }

        @Test
        fun `기본 채널 unknown으로 E2E 레이턴시가 기록되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordE2eLatency(durationMs = 500)

            val timer = registry.find("arc.sla.e2e.latency")
                .tag("channel", "unknown")
                .timer()

            assertEquals(
                1L,
                timer!!.count(),
                "기본 채널 'unknown'으로 E2E 레이턴시가 기록되어야 한다"
            )
        }

        @Test
        fun `여러 채널의 레이턴시가 개별로 기록되어야 한다`() {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerSlaMetrics(registry)

            metrics.recordE2eLatency(durationMs = 100, channel = "rest")
            metrics.recordE2eLatency(durationMs = 300, channel = "rest")
            metrics.recordE2eLatency(durationMs = 200, channel = "teams")

            val restTimer = registry.find("arc.sla.e2e.latency").tag("channel", "rest").timer()
            val teamsTimer = registry.find("arc.sla.e2e.latency").tag("channel", "teams").timer()

            assertEquals(2L, restTimer!!.count(), "rest 채널에 2개 샘플이 기록되어야 한다")
            assertEquals(1L, teamsTimer!!.count(), "teams 채널에 1개 샘플이 기록되어야 한다")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NoOpSlaMetrics
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class NoOpSlaMetricsBehavior {

        @Test
        fun `모든 메서드가 예외 없이 통과해야 한다`() {
            val noOp = NoOpSlaMetrics()

            // 예외 없이 실행되어야 한다
            noOp.recordReActConvergence(steps = 5, stopReason = "completed", durationMs = 1000)
            noOp.recordToolFailureDetail("some_tool", "timeout", "err", 500)
            noOp.recordAvailabilitySample(healthy = true)
            noOp.recordAvailabilitySample(healthy = false)
            noOp.recordE2eLatency(durationMs = 250, channel = "rest")

            // 여기까지 도달하면 성공
            assertTrue(true) { "NoOpSlaMetrics의 모든 메서드가 예외 없이 실행되어야 한다" }
        }
    }
}
