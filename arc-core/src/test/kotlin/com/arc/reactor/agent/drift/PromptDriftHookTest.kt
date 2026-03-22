package com.arc.reactor.agent.drift

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

@DisplayName("PromptDriftHook")
class PromptDriftHookTest {

    private lateinit var detector: PromptDriftDetector
    private lateinit var hook: PromptDriftHook

    @BeforeEach
    fun setUp() {
        detector = mockk(relaxed = true)
        hook = PromptDriftHook(detector, evaluationInterval = 5)
    }

    private fun hookContext(prompt: String = "hello") = HookContext(
        runId = "test-run",
        userId = "user-1",
        userPrompt = prompt
    )

    private fun agentResponse(
        response: String? = "response text"
    ) = AgentResponse(
        success = true,
        response = response,
        totalDurationMs = 1000
    )

    @Nested
    @DisplayName("입출력 길이 기록")
    inner class RecordLengths {

        @Test
        fun `입력 길이를 올바르게 기록한다`() = runTest {
            every { detector.evaluate() } returns emptyList()

            hook.afterAgentComplete(
                hookContext("hello world"),
                agentResponse()
            )

            verify { detector.recordInput(11) }
        }

        @Test
        fun `출력 길이를 올바르게 기록한다`() = runTest {
            every { detector.evaluate() } returns emptyList()

            hook.afterAgentComplete(
                hookContext(),
                agentResponse("short reply")
            )

            verify { detector.recordOutput(11) }
        }

        @Test
        fun `null 응답은 길이 0으로 기록한다`() = runTest {
            every { detector.evaluate() } returns emptyList()

            hook.afterAgentComplete(
                hookContext(),
                agentResponse(response = null)
            )

            verify { detector.recordOutput(0) }
        }

        @Test
        fun `빈 입력을 올바르게 기록한다`() = runTest {
            every { detector.evaluate() } returns emptyList()

            hook.afterAgentComplete(
                hookContext(""),
                agentResponse()
            )

            verify { detector.recordInput(0) }
        }
    }

    @Nested
    @DisplayName("주기적 평가")
    inner class PeriodicEvaluation {

        @Test
        fun `평가 주기에 해당하는 요청에서만 평가한다`() = runTest {
            every { detector.evaluate() } returns emptyList()
            val ctx = hookContext()
            val resp = agentResponse()

            // 5회 주기이므로 1~4번째에는 평가하지 않음
            repeat(4) { hook.afterAgentComplete(ctx, resp) }
            verify(exactly = 0) { detector.evaluate() }

            // 5번째에 평가
            hook.afterAgentComplete(ctx, resp)
            verify(exactly = 1) { detector.evaluate() }
        }

        @Test
        fun `평가 주기가 반복적으로 동작한다`() = runTest {
            every { detector.evaluate() } returns emptyList()
            val ctx = hookContext()
            val resp = agentResponse()

            repeat(10) { hook.afterAgentComplete(ctx, resp) }

            verify(exactly = 2) { detector.evaluate() }
        }

        @Test
        fun `드리프트 감지 시 예외 없이 완료된다`() = runTest {
            val anomaly = DriftAnomaly(
                type = DriftType.INPUT_LENGTH,
                currentMean = 5000.0,
                baselineMean = 500.0,
                standardDeviation = 50.0,
                deviationFactor = 90.0,
                message = "입력 길이 드리프트"
            )
            every { detector.evaluate() } returns listOf(anomaly)
            val ctx = hookContext()
            val resp = agentResponse()

            // 5번째 요청에서 평가 + WARN 로깅 (예외 없이 완료)
            repeat(5) { hook.afterAgentComplete(ctx, resp) }
        }
    }

    @Nested
    @DisplayName("fail-open 정책")
    inner class FailOpen {

        @Test
        fun `Hook 속성이 올바르게 설정되어 있다`() {
            hook.order shouldBe 230
            hook.failOnError shouldBe false
        }

        @Test
        fun `감지기 recordInput 예외가 발생해도 Hook이 실패하지 않는다`() =
            runTest {
                every {
                    detector.recordInput(any())
                } throws RuntimeException("기록 오류")

                // 예외가 전파되지 않으면 성공
                hook.afterAgentComplete(hookContext(), agentResponse())
            }

        @Test
        fun `감지기 recordOutput 예외가 발생해도 Hook이 실패하지 않는다`() =
            runTest {
                every {
                    detector.recordOutput(any())
                } throws RuntimeException("기록 오류")

                // 예외가 전파되지 않으면 성공
                hook.afterAgentComplete(hookContext(), agentResponse())
            }

        @Test
        fun `평가 예외가 발생해도 Hook이 실패하지 않는다`() = runTest {
            every {
                detector.evaluate()
            } throws RuntimeException("평가 오류")
            val ctx = hookContext()
            val resp = agentResponse()

            // 5번째 요청에서 평가 시도하나 예외 전파되지 않음
            repeat(5) { hook.afterAgentComplete(ctx, resp) }
        }
    }
}
