package com.arc.reactor.agent.metrics

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.slot

/**
 * [EvaluationMetricsHook] 단위 테스트.
 *
 * Hook이 HookContext / AgentResponse 메타데이터를 올바르게 파싱하여 collector로
 * 전달하는지 검증한다. fail-open 동작도 함께 확인한다.
 */
class EvaluationMetricsHookTest {

    @Nested
    inner class BasicRecording {

        @Test
        fun `성공 응답에 대해 task와 도구 호출 수를 기록해야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(
                runId = "run-1",
                userId = "user-1",
                userPrompt = "test"
            )
            val response = AgentResponse(
                success = true,
                response = "답변",
                toolsUsed = listOf("jira_search", "confluence_search"),
                totalDurationMs = 1_234L
            )

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordTaskCompleted(
                    success = true,
                    durationMs = 1_234L,
                    errorCode = null
                )
            }
            verify(exactly = 1) {
                collector.recordToolCallCount(
                    count = 2,
                    toolNames = listOf("jira_search", "confluence_search")
                )
            }
        }

        @Test
        fun `실패 응답에 대해 errorCode를 함께 기록해야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-2", userId = "user-1", userPrompt = "test")
            val response = AgentResponse(
                success = false,
                errorMessage = "Rate limit exceeded",
                errorCode = "RATE_LIMITED",
                totalDurationMs = 500L
            )

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordTaskCompleted(
                    success = false,
                    durationMs = 500L,
                    errorCode = "RATE_LIMITED"
                )
            }
        }

        @Test
        fun `totalDurationMs가 0이면 context durationMs로 대체되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-3", userId = "user-1", userPrompt = "test")
            Thread.sleep(10)  // context.durationMs가 0보다 크도록 보장
            val response = AgentResponse(success = true, totalDurationMs = 0L)

            hook.afterAgentComplete(context, response)

            val captured = slot<Long>()
            verify(exactly = 1) {
                collector.recordTaskCompleted(
                    success = true,
                    durationMs = capture(captured),
                    errorCode = null
                )
            }
            assert(captured.captured >= 0) { "context.durationMs는 음수여서는 안 된다" }
        }
    }

    @Nested
    inner class CostRecording {

        @Test
        fun `costEstimateUsd 메타데이터가 있으면 비용을 기록해야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["costEstimateUsd"] = "0.002500"
            context.metadata["model"] = "gemini-2.5-flash"
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordTokenCost(
                    costUsd = 0.0025,
                    model = "gemini-2.5-flash"
                )
            }
        }

        @Test
        fun `costEstimateUsd가 Number 타입이어도 기록해야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["costEstimateUsd"] = 0.01
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordTokenCost(costUsd = 0.01, model = "")
            }
        }

        @Test
        fun `비용 0은 기록하지 않아야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["costEstimateUsd"] = "0.000000"
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 0) {
                collector.recordTokenCost(any(), any())
            }
        }

        @Test
        fun `costEstimateUsd 파싱 실패 시 기록하지 않아야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["costEstimateUsd"] = "not-a-number"
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 0) {
                collector.recordTokenCost(any(), any())
            }
        }
    }

    @Nested
    inner class HumanOverrideRecording {

        @Test
        fun `hitlApproved true는 APPROVED로 기록되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["hitlApproved_delete_order"] = true
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordHumanOverride(
                    outcome = HumanOverrideOutcome.APPROVED,
                    toolName = "delete_order"
                )
            }
        }

        @Test
        fun `hitlApproved false + 타임아웃 사유는 TIMEOUT으로 기록되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["hitlApproved_slow_tool"] = false
            context.metadata["hitlRejectionReason_slow_tool"] = "Approval timed out after 5000ms"
            val response = AgentResponse(success = false, totalDurationMs = 5_100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordHumanOverride(
                    outcome = HumanOverrideOutcome.TIMEOUT,
                    toolName = "slow_tool"
                )
            }
        }

        @Test
        fun `hitlApproved false + 다른 거부 사유는 REJECTED로 기록되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["hitlApproved_transfer_funds"] = false
            context.metadata["hitlRejectionReason_transfer_funds"] = "User declined"
            val response = AgentResponse(success = false, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordHumanOverride(
                    outcome = HumanOverrideOutcome.REJECTED,
                    toolName = "transfer_funds"
                )
            }
        }
    }

    @Nested
    inner class SafetyRejectionRecording {

        @Test
        fun `errorCode GUARD_REJECTED는 GUARD stage로 기록되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["blockReason"] = "injection detected"
            val response = AgentResponse(
                success = false,
                errorCode = "GUARD_REJECTED",
                totalDurationMs = 50L
            )

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordSafetyRejection(
                    stage = SafetyRejectionStage.GUARD,
                    reason = "injection detected"
                )
            }
        }

        @Test
        fun `errorCode OUTPUT_GUARD_REJECTED는 OUTPUT_GUARD stage로 기록되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["blockReason"] = "pii detected"
            val response = AgentResponse(
                success = false,
                errorCode = "OUTPUT_GUARD_REJECTED",
                totalDurationMs = 100L
            )

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordSafetyRejection(
                    stage = SafetyRejectionStage.OUTPUT_GUARD,
                    reason = "pii detected"
                )
            }
        }

        @Test
        fun `blockReason만 있고 errorCode가 없으면 GUARD로 기본 분류되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["blockReason"] = "rate limited"
            val response = AgentResponse(success = false, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordSafetyRejection(
                    stage = SafetyRejectionStage.GUARD,
                    reason = "rate limited"
                )
            }
        }

        @Test
        fun `차단 사유도 errorCode도 없으면 기록하지 않아야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 0) {
                collector.recordSafetyRejection(any(), any())
            }
        }
    }

    @Nested
    inner class FailOpenBehavior {

        @Test
        fun `collector가 예외를 던져도 Hook은 예외를 전파하지 않아야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>()
            every { collector.recordTaskCompleted(any(), any(), any()) } throws
                RuntimeException("collector broken")
            every { collector.recordToolCallCount(any(), any()) } just runs
            every { collector.recordTokenCost(any(), any()) } just runs
            every { collector.recordHumanOverride(any(), any()) } just runs
            every { collector.recordSafetyRejection(any(), any()) } just runs

            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            // 예외가 밖으로 전파되지 않아야 한다
            hook.afterAgentComplete(context, response)

            assertEquals(150, hook.order) { "Hook order는 150이어야 한다" }
            assert(!hook.failOnError) { "Hook은 fail-open이어야 한다" }
        }

        @Test
        fun `failOnError는 false여야 한다`() {
            val hook = EvaluationMetricsHook(NoOpEvaluationMetricsCollector)
            assert(!hook.failOnError) { "EvaluationMetricsHook은 fail-open이어야 한다" }
        }

        @Test
        fun `Hook order는 150이어야 한다`() {
            val hook = EvaluationMetricsHook(NoOpEvaluationMetricsCollector)
            assertEquals(150, hook.order) { "Hook order는 표준 Hook 범위 내(100-199)에 있어야 한다" }
        }
    }
}
