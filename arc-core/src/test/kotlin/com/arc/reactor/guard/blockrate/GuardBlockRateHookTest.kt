package com.arc.reactor.guard.blockrate

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

@DisplayName("GuardBlockRateHook")
class GuardBlockRateHookTest {

    private lateinit var monitor: GuardBlockRateMonitor
    private lateinit var hook: GuardBlockRateHook

    @BeforeEach
    fun setUp() {
        monitor = mockk(relaxed = true)
        hook = GuardBlockRateHook(monitor, evaluationInterval = 5)
    }

    private fun hookContext(
        guardBlocked: Any? = null
    ): HookContext {
        val ctx = HookContext(
            runId = "test-run",
            userId = "user-1",
            userPrompt = "hello"
        )
        if (guardBlocked != null) {
            ctx.metadata["guardBlocked"] = guardBlocked
        }
        return ctx
    }

    private fun agentResponse() = AgentResponse(
        success = true,
        response = "response",
        totalDurationMs = 1000
    )

    @Nested
    @DisplayName("결과 기록")
    inner class RecordResults {

        @Test
        fun `Boolean 차단 결과를 올바르게 기록한다`() = runTest {
            every { monitor.evaluate() } returns emptyList()

            hook.afterAgentComplete(
                hookContext(guardBlocked = true),
                agentResponse()
            )

            verify { monitor.recordGuardResult(true) }
        }

        @Test
        fun `Boolean 허용 결과를 올바르게 기록한다`() = runTest {
            every { monitor.evaluate() } returns emptyList()

            hook.afterAgentComplete(
                hookContext(guardBlocked = false),
                agentResponse()
            )

            verify { monitor.recordGuardResult(false) }
        }

        @Test
        fun `문자열 true를 올바르게 기록한다`() = runTest {
            every { monitor.evaluate() } returns emptyList()

            hook.afterAgentComplete(
                hookContext(guardBlocked = "true"),
                agentResponse()
            )

            verify { monitor.recordGuardResult(true) }
        }

        @Test
        fun `문자열 false를 올바르게 기록한다`() = runTest {
            every { monitor.evaluate() } returns emptyList()

            hook.afterAgentComplete(
                hookContext(guardBlocked = "false"),
                agentResponse()
            )

            verify { monitor.recordGuardResult(false) }
        }

        @Test
        fun `guardBlocked 메타데이터가 없으면 기록하지 않는다`() = runTest {
            hook.afterAgentComplete(hookContext(), agentResponse())

            verify(exactly = 0) { monitor.recordGuardResult(any()) }
        }

        @Test
        fun `파싱 불가능한 문자열이면 기록하지 않는다`() = runTest {
            hook.afterAgentComplete(
                hookContext(guardBlocked = "not-a-boolean"),
                agentResponse()
            )

            verify(exactly = 0) { monitor.recordGuardResult(any()) }
        }

        @Test
        fun `지원하지 않는 타입이면 기록하지 않는다`() = runTest {
            hook.afterAgentComplete(
                hookContext(guardBlocked = 42),
                agentResponse()
            )

            verify(exactly = 0) { monitor.recordGuardResult(any()) }
        }
    }

    @Nested
    @DisplayName("주기적 평가")
    inner class PeriodicEvaluation {

        @Test
        fun `평가 주기에 해당하는 요청에서만 평가한다`() = runTest {
            every { monitor.evaluate() } returns emptyList()
            val ctx = hookContext(guardBlocked = false)
            val resp = agentResponse()

            // 5회 주기이므로 1~4번째에는 평가하지 않음
            repeat(4) { hook.afterAgentComplete(ctx, resp) }
            verify(exactly = 0) { monitor.evaluate() }

            // 5번째에 평가
            hook.afterAgentComplete(ctx, resp)
            verify(exactly = 1) { monitor.evaluate() }
        }

        @Test
        fun `평가 주기가 반복적으로 동작한다`() = runTest {
            every { monitor.evaluate() } returns emptyList()
            val ctx = hookContext(guardBlocked = true)
            val resp = agentResponse()

            repeat(10) { hook.afterAgentComplete(ctx, resp) }

            verify(exactly = 2) { monitor.evaluate() }
        }

        @Test
        fun `이상 감지 시 예외 없이 완료된다`() = runTest {
            val anomaly = GuardBlockRateAnomaly(
                type = GuardAnomalyType.SPIKE,
                currentRate = 0.5,
                baselineRate = 0.1,
                message = "Guard 차단률 급증"
            )
            every { monitor.evaluate() } returns listOf(anomaly)
            val ctx = hookContext(guardBlocked = true)
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
            hook.order shouldBe 240
            hook.failOnError shouldBe false
        }

        @Test
        fun `모니터 recordGuardResult 예외가 발생해도 Hook이 실패하지 않는다`() =
            runTest {
                every {
                    monitor.recordGuardResult(any())
                } throws RuntimeException("기록 오류")

                // 예외가 전파되지 않으면 성공
                hook.afterAgentComplete(
                    hookContext(guardBlocked = true),
                    agentResponse()
                )
            }

        @Test
        fun `평가 예외가 발생해도 Hook이 실패하지 않는다`() = runTest {
            every {
                monitor.evaluate()
            } throws RuntimeException("평가 오류")
            val ctx = hookContext(guardBlocked = false)
            val resp = agentResponse()

            // 5번째 요청에서 평가 시도하나 예외 전파되지 않음
            repeat(5) { hook.afterAgentComplete(ctx, resp) }
        }
    }
}
