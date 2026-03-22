package com.arc.reactor.agent.slo

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("SloAlertHook")
class SloAlertHookTest {

    private lateinit var evaluator: SloAlertEvaluator
    private lateinit var notifier: SloAlertNotifier
    private lateinit var hook: SloAlertHook

    @BeforeEach
    fun setUp() {
        evaluator = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        hook = SloAlertHook(evaluator, notifier)
    }

    private fun hookContext() = HookContext(
        runId = "test-run",
        userId = "user-1",
        userPrompt = "hello"
    )

    private fun agentResponse(
        success: Boolean = true,
        durationMs: Long = 1000
    ) = AgentResponse(
        success = success,
        response = "response",
        totalDurationMs = durationMs
    )

    @Nested
    @DisplayName("메트릭 기록")
    inner class RecordMetrics {

        @Test
        fun `완료 시 레이턴시와 결과를 기록한다`() = runTest {
            every { evaluator.evaluate() } returns emptyList()

            hook.afterAgentComplete(hookContext(), agentResponse(true, 1500))

            verify { evaluator.recordLatency(1500) }
            verify { evaluator.recordResult(true) }
        }

        @Test
        fun `실패 시에도 결과를 기록한다`() = runTest {
            every { evaluator.evaluate() } returns emptyList()

            hook.afterAgentComplete(hookContext(), agentResponse(false, 500))

            verify { evaluator.recordLatency(500) }
            verify { evaluator.recordResult(false) }
        }
    }

    @Nested
    @DisplayName("위반 알림")
    inner class ViolationNotification {

        @Test
        fun `위반이 있으면 알림을 발송한다`() = runTest {
            val violation = SloViolation(
                type = SloViolationType.LATENCY,
                currentValue = 3000.0,
                threshold = 2000.0,
                message = "P95 레이턴시 초과"
            )
            every { evaluator.evaluate() } returns listOf(violation)

            hook.afterAgentComplete(hookContext(), agentResponse())

            coVerify { notifier.notify(match { it shouldHaveSize 1; true }) }
        }

        @Test
        fun `위반이 없으면 알림을 발송하지 않는다`() = runTest {
            every { evaluator.evaluate() } returns emptyList()

            hook.afterAgentComplete(hookContext(), agentResponse())

            coVerify(exactly = 0) { notifier.notify(any()) }
        }
    }

    @Nested
    @DisplayName("fail-open 정책")
    inner class FailOpen {

        @Test
        fun `Hook 속성이 올바르게 설정되어 있다`() {
            hook.order shouldBe 210
            hook.failOnError shouldBe false
        }

        @Test
        fun `평가기 예외가 발생해도 Hook이 실패하지 않는다`() = runTest {
            every { evaluator.evaluate() } throws RuntimeException("평가 오류")

            // 예외가 전파되지 않으면 성공
            hook.afterAgentComplete(hookContext(), agentResponse())
        }

        @Test
        fun `알림기 예외가 발생해도 Hook이 실패하지 않는다`() = runTest {
            val violation = SloViolation(
                type = SloViolationType.ERROR_RATE,
                currentValue = 0.1,
                threshold = 0.05,
                message = "에러율 초과"
            )
            every { evaluator.evaluate() } returns listOf(violation)
            coEvery { notifier.notify(any()) } throws RuntimeException("알림 오류")

            // 예외가 전파되지 않으면 성공
            hook.afterAgentComplete(hookContext(), agentResponse())
        }
    }
}
