package com.arc.reactor.agent.metrics

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.tool.summarize.SummaryKind
import com.arc.reactor.tool.summarize.ToolResponseSummary
import com.arc.reactor.tool.summarize.ToolResponseSummarizerHook
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
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

            // R339: 공백은 `_`로 정규화되어 Prometheus tag에 안전한 형태로 기록됨
            verify(exactly = 1) {
                collector.recordSafetyRejection(
                    stage = SafetyRejectionStage.GUARD,
                    reason = "injection_detected"
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

            // R339: 공백 정규화 — "pii detected" → "pii_detected"
            verify(exactly = 1) {
                collector.recordSafetyRejection(
                    stage = SafetyRejectionStage.OUTPUT_GUARD,
                    reason = "pii_detected"
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

            // R339: 공백 정규화 — "rate limited" → "rate_limited"
            verify(exactly = 1) {
                collector.recordSafetyRejection(
                    stage = SafetyRejectionStage.GUARD,
                    reason = "rate_limited"
                )
            }
        }

        /**
         * R339 regression: `blockReason`은 Guard/Hook이 자유롭게 설정하는 자유 문자열이라
         * 사용자 쿼리 일부, PII, 한글, 동적 식별자 등을 포함할 수 있다. Micrometer `reason`
         * tag에 그대로 전달하면 Prometheus 시계열 카디널리티가 폭발하므로 고정 길이 + alphanumeric
         * 집합으로 정규화되어야 한다.
         */
        @Test
        fun `R339 긴 자유 문자열 blockReason은 64자 + alphanumeric tag로 정규화되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            // 한글 + 특수문자 + 쿼리 ID + 80자 길이 — 전형적인 병적 자유 문자열
            context.metadata["blockReason"] =
                "사용자 쿼리에서 PII 감지: email=alice@example.com, id=#12345 (IP: 10.0.0.1)"
            val response = AgentResponse(success = false, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordSafetyRejection(
                    stage = SafetyRejectionStage.GUARD,
                    reason = match { tag ->
                        val lengthOk = tag.length <= 64
                        val charsOk = tag.all { it.isLetterOrDigit() || it in setOf('_', '-', '.') }
                        lengthOk && charsOk && tag.isNotBlank()
                    }
                )
            }
        }

        @Test
        fun `R339 blank blockReason은 unknown으로 폴백되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            context.metadata["blockReason"] = "   " // 공백만
            val response = AgentResponse(
                success = false,
                errorCode = "GUARD_REJECTED",
                totalDurationMs = 50L
            )

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordSafetyRejection(
                    stage = SafetyRejectionStage.GUARD,
                    reason = MicrometerEvaluationMetricsCollector.UNKNOWN_TAG
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

    @Nested
    inner class ToolResponseKindRecording {

        @Test
        fun `R223 toolSummary 메타데이터가 있으면 SummaryKind별 카운터를 기록해야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            context.metadata[ToolResponseSummarizerHook.buildKey(0, "jira_search")] =
                ToolResponseSummary(
                    text = "issues: 3건 [HRFW-1, HRFW-2, HRFW-3]",
                    kind = SummaryKind.LIST_TOP_N,
                    originalLength = 500,
                    itemCount = 3,
                    primaryKey = "HRFW-1"
                )
            context.metadata[ToolResponseSummarizerHook.buildKey(1, "confluence_search")] =
                ToolResponseSummary(
                    text = "필드(4): id, title, body, version",
                    kind = SummaryKind.STRUCTURED,
                    originalLength = 300
                )
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordToolResponseKind(
                    kind = "list_top_n",
                    toolName = "jira_search"
                )
            }
            verify(exactly = 1) {
                collector.recordToolResponseKind(
                    kind = "structured",
                    toolName = "confluence_search"
                )
            }
        }

        @Test
        fun `동일 도구의 여러 호출은 각각 기록되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            context.metadata[ToolResponseSummarizerHook.buildKey(0, "jira_search")] =
                ToolResponseSummary(
                    text = "issues: 2건",
                    kind = SummaryKind.LIST_TOP_N,
                    originalLength = 100
                )
            context.metadata[ToolResponseSummarizerHook.buildKey(1, "jira_search")] =
                ToolResponseSummary(
                    text = "issues: 5건",
                    kind = SummaryKind.LIST_TOP_N,
                    originalLength = 200
                )
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 2) {
                collector.recordToolResponseKind(
                    kind = "list_top_n",
                    toolName = "jira_search"
                )
            }
        }

        @Test
        fun `toolSummary 엔트리가 없으면 recordToolResponseKind를 호출하지 않아야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            // toolSummary_* 없음
            val response = AgentResponse(
                success = true,
                toolsUsed = listOf("jira_search"),
                totalDurationMs = 100L
            )

            hook.afterAgentComplete(context, response)

            verify(exactly = 0) {
                collector.recordToolResponseKind(any(), any())
            }
        }

        @Test
        fun `toolSummaryCount 카운터는 recordToolResponseKind 대상이 아니어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            context.metadata[ToolResponseSummarizerHook.COUNTER_KEY] = 3
            context.metadata[ToolResponseSummarizerHook.buildKey(0, "jira_search")] =
                ToolResponseSummary(
                    text = "test",
                    kind = SummaryKind.TEXT_FULL,
                    originalLength = 4
                )
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordToolResponseKind(kind = "text_full", toolName = "jira_search")
            }
        }

        @Test
        fun `ToolResponseSummary가 아닌 값은 무시되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            // 잘못된 타입의 값
            context.metadata[ToolResponseSummarizerHook.buildKey(0, "jira_search")] = "raw string"
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 0) {
                collector.recordToolResponseKind(any(), any())
            }
        }

        @Test
        fun `underscore 포함 도구 이름이 올바르게 파싱되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            // 도구 이름에 언더스코어가 여러 개 포함
            context.metadata[ToolResponseSummarizerHook.buildKey(2, "confluence_search_by_text")] =
                ToolResponseSummary(
                    text = "empty",
                    kind = SummaryKind.EMPTY,
                    originalLength = 0
                )
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordToolResponseKind(
                    kind = "empty",
                    toolName = "confluence_search_by_text"
                )
            }
        }
    }

    @Nested
    inner class ToolResponseCompressionRecording {

        @Test
        fun `R242 toolSummary가 있으면 compressionPercent를 기록해야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            // 원본 1000자 → 요약 250자 = 75% 압축
            context.metadata[ToolResponseSummarizerHook.buildKey(0, "jira_search")] =
                ToolResponseSummary(
                    text = "x".repeat(250),
                    kind = SummaryKind.LIST_TOP_N,
                    originalLength = 1000,
                    itemCount = 3
                )
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordToolResponseCompression(
                    percent = 75,
                    toolName = "jira_search"
                )
            }
        }

        @Test
        fun `kind 기록과 compression 기록이 같은 요약에서 쌍으로 호출되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            context.metadata[ToolResponseSummarizerHook.buildKey(0, "confluence_get_page")] =
                ToolResponseSummary(
                    text = "요약",
                    kind = SummaryKind.TEXT_HEAD_TAIL,
                    originalLength = 500
                )
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verifyOrder {
                collector.recordToolResponseKind(
                    kind = "text_head_tail",
                    toolName = "confluence_get_page"
                )
                collector.recordToolResponseCompression(
                    percent = 99, // (500-6)/500 → 98.8 → 98 (truncated)
                    toolName = "confluence_get_page"
                )
            }
        }

        @Test
        fun `여러 도구의 압축률이 각각 기록되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            // 도구 A: 90% 압축
            context.metadata[ToolResponseSummarizerHook.buildKey(0, "jira_search")] =
                ToolResponseSummary(
                    text = "x".repeat(100),
                    kind = SummaryKind.LIST_TOP_N,
                    originalLength = 1000
                )
            // 도구 B: 50% 압축
            context.metadata[ToolResponseSummarizerHook.buildKey(1, "bitbucket_list_prs")] =
                ToolResponseSummary(
                    text = "x".repeat(500),
                    kind = SummaryKind.LIST_TOP_N,
                    originalLength = 1000
                )
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordToolResponseCompression(percent = 90, toolName = "jira_search")
            }
            verify(exactly = 1) {
                collector.recordToolResponseCompression(percent = 50, toolName = "bitbucket_list_prs")
            }
        }

        @Test
        fun `toolSummary 없으면 recordToolResponseCompression를 호출하지 않아야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            val response = AgentResponse(
                success = true,
                toolsUsed = listOf("jira_search"),
                totalDurationMs = 100L
            )

            hook.afterAgentComplete(context, response)

            verify(exactly = 0) {
                collector.recordToolResponseCompression(any(), any())
            }
        }

        @Test
        fun `원본 0인 빈 응답도 compressionPercent 0으로 기록되어야 한다`() = runTest {
            val collector = mockk<EvaluationMetricsCollector>(relaxed = true)
            val hook = EvaluationMetricsHook(collector)
            val context = HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            context.metadata[ToolResponseSummarizerHook.buildKey(0, "empty_tool")] =
                ToolResponseSummary(
                    text = "",
                    kind = SummaryKind.EMPTY,
                    originalLength = 0
                )
            val response = AgentResponse(success = true, totalDurationMs = 100L)

            hook.afterAgentComplete(context, response)

            verify(exactly = 1) {
                collector.recordToolResponseCompression(
                    percent = 0,
                    toolName = "empty_tool"
                )
            }
        }
    }
}
