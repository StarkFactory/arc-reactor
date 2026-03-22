package com.arc.reactor.agent.budget

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("CostAnomalyHook")
class CostAnomalyHookTest {

    private lateinit var detector: CostAnomalyDetector
    private lateinit var hook: CostAnomalyHook

    @BeforeEach
    fun setUp() {
        detector = mockk(relaxed = true)
        hook = CostAnomalyHook(detector)
    }

    private fun hookContext(
        costEstimateUsd: Any? = null
    ): HookContext {
        val ctx = HookContext(
            runId = "test-run",
            userId = "user-1",
            userPrompt = "hello"
        )
        if (costEstimateUsd != null) {
            ctx.metadata["costEstimateUsd"] = costEstimateUsd
        }
        return ctx
    }

    private fun agentResponse() = AgentResponse(
        success = true,
        response = "response",
        totalDurationMs = 1000
    )

    @Nested
    @DisplayName("비용 기록")
    inner class RecordCost {

        @Test
        fun `메타데이터에서 문자열 비용을 추출하여 기록한다`() = runTest {
            every { detector.evaluate() } returns null

            hook.afterAgentComplete(
                hookContext(costEstimateUsd = "0.005000"),
                agentResponse()
            )

            verify { detector.recordCost(0.005) }
        }

        @Test
        fun `메타데이터에서 숫자 비용을 추출하여 기록한다`() = runTest {
            every { detector.evaluate() } returns null

            hook.afterAgentComplete(
                hookContext(costEstimateUsd = 0.01),
                agentResponse()
            )

            verify { detector.recordCost(0.01) }
        }

        @Test
        fun `비용 메타데이터가 없으면 기록하지 않는다`() = runTest {
            hook.afterAgentComplete(hookContext(), agentResponse())

            verify(exactly = 0) { detector.recordCost(any()) }
        }

        @Test
        fun `비용 메타데이터가 파싱 불가능한 문자열이면 기록하지 않는다`() = runTest {
            hook.afterAgentComplete(
                hookContext(costEstimateUsd = "not-a-number"),
                agentResponse()
            )

            verify(exactly = 0) { detector.recordCost(any()) }
        }
    }

    @Nested
    @DisplayName("이상 감지")
    inner class AnomalyDetection {

        @Test
        fun `이상이 감지되면 경고 로그만 남긴다`() = runTest {
            val anomaly = CostAnomaly(
                currentCost = 0.10,
                baselineCost = 0.01,
                multiplier = 10.0,
                threshold = 3.0,
                message = "비용 이상 탐지"
            )
            every { detector.evaluate() } returns anomaly

            // 예외 없이 완료되면 성공 (WARN 로그는 내부에서 처리)
            hook.afterAgentComplete(
                hookContext(costEstimateUsd = "0.100000"),
                agentResponse()
            )

            verify { detector.recordCost(0.1) }
            verify { detector.evaluate() }
        }

        @Test
        fun `이상이 없으면 경고 없이 완료된다`() = runTest {
            every { detector.evaluate() } returns null

            hook.afterAgentComplete(
                hookContext(costEstimateUsd = "0.010000"),
                agentResponse()
            )

            verify { detector.recordCost(0.01) }
            verify { detector.evaluate() }
        }
    }

    @Nested
    @DisplayName("fail-open 정책")
    inner class FailOpen {

        @Test
        fun `Hook 속성이 올바르게 설정되어 있다`() {
            hook.order shouldBe 220
            hook.failOnError shouldBe false
        }

        @Test
        fun `탐지기 예외가 발생해도 Hook이 실패하지 않는다`() = runTest {
            every { detector.recordCost(any()) } throws RuntimeException("탐지 오류")

            // 예외가 전파되지 않으면 성공
            hook.afterAgentComplete(
                hookContext(costEstimateUsd = "0.010000"),
                agentResponse()
            )
        }

        @Test
        fun `평가 예외가 발생해도 Hook이 실패하지 않는다`() = runTest {
            every { detector.evaluate() } throws RuntimeException("평가 오류")

            // 예외가 전파되지 않으면 성공
            hook.afterAgentComplete(
                hookContext(costEstimateUsd = "0.010000"),
                agentResponse()
            )
        }
    }
}
