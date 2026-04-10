package com.arc.reactor.agent.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [EvaluationMetricsCollector] 및 구현체 단위 테스트.
 *
 * R222 Directive #5 Benchmark-Aware Evaluation Loop의 메트릭 수집 레이어 검증.
 */
class EvaluationMetricsCollectorTest {

    @Nested
    inner class NoOpBehavior {

        @Test
        fun `NoOp 수집기는 모든 메서드를 안전하게 처리해야 한다`() {
            val collector = NoOpEvaluationMetricsCollector
            // 어떤 호출도 예외를 던지지 않아야 한다
            collector.recordTaskCompleted(success = true, durationMs = 1_000L)
            collector.recordTaskCompleted(success = false, durationMs = 500L, errorCode = "TIMEOUT")
            collector.recordToolCallCount(count = 3, toolNames = listOf("a", "b", "c"))
            collector.recordTokenCost(costUsd = 0.0025, model = "gemini-flash")
            collector.recordHumanOverride(HumanOverrideOutcome.APPROVED, "delete_order")
            collector.recordSafetyRejection(SafetyRejectionStage.GUARD, "injection")
            // 도달만 해도 성공
            assertTrue(true) { "NoOp 수집기의 모든 메서드는 예외 없이 반환해야 한다" }
        }

        @Test
        fun `NoOp 수집기는 음수 비용도 안전하게 처리해야 한다`() {
            NoOpEvaluationMetricsCollector.recordTokenCost(-1.0, "unknown")
            assertTrue(true) { "NoOp은 입력 검증 없이 모든 값을 수용해야 한다" }
        }

        @Test
        fun `NoOp 수집기는 빈 문자열 인자도 안전하게 처리해야 한다`() {
            NoOpEvaluationMetricsCollector.recordHumanOverride(HumanOverrideOutcome.APPROVED, "")
            NoOpEvaluationMetricsCollector.recordSafetyRejection(SafetyRejectionStage.HOOK, "")
            assertTrue(true) { "빈 문자열도 예외 없이 처리되어야 한다" }
        }
    }

    @Nested
    inner class EnumDefinitions {

        @Test
        fun `HumanOverrideOutcome은 4개 값을 가져야 한다`() {
            val values = HumanOverrideOutcome.values()
            assertEquals(4, values.size) { "HumanOverrideOutcome는 정확히 4개 값이어야 한다" }
            assertTrue(HumanOverrideOutcome.APPROVED in values) { "APPROVED가 포함되어야 한다" }
            assertTrue(HumanOverrideOutcome.REJECTED in values) { "REJECTED가 포함되어야 한다" }
            assertTrue(HumanOverrideOutcome.TIMEOUT in values) { "TIMEOUT이 포함되어야 한다" }
            assertTrue(HumanOverrideOutcome.AUTO in values) { "AUTO가 포함되어야 한다" }
        }

        @Test
        fun `SafetyRejectionStage는 5개 값을 가져야 한다`() {
            val values = SafetyRejectionStage.values()
            assertEquals(5, values.size) { "SafetyRejectionStage는 정확히 5개 값이어야 한다" }
            assertTrue(SafetyRejectionStage.GUARD in values) { "GUARD가 포함되어야 한다" }
            assertTrue(SafetyRejectionStage.OUTPUT_GUARD in values) { "OUTPUT_GUARD가 포함되어야 한다" }
            assertTrue(SafetyRejectionStage.HOOK in values) { "HOOK이 포함되어야 한다" }
            assertTrue(SafetyRejectionStage.TOOL_POLICY in values) { "TOOL_POLICY가 포함되어야 한다" }
            assertTrue(SafetyRejectionStage.OTHER in values) { "OTHER가 포함되어야 한다" }
        }
    }

    @Nested
    inner class MicrometerBehavior {

        private fun newCollector(): Pair<SimpleMeterRegistry, MicrometerEvaluationMetricsCollector> {
            val registry = SimpleMeterRegistry()
            return registry to MicrometerEvaluationMetricsCollector(registry)
        }

        @Test
        fun `task 완료 성공은 success 태그가 있는 카운터를 증가시켜야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordTaskCompleted(success = true, durationMs = 1_234L)

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TASK_COMPLETED)
                .tag(MicrometerEvaluationMetricsCollector.TAG_RESULT, MicrometerEvaluationMetricsCollector.RESULT_SUCCESS)
                .tag(MicrometerEvaluationMetricsCollector.TAG_ERROR_CODE, MicrometerEvaluationMetricsCollector.NONE_TAG)
                .counter()
            assertNotNull(counter) { "success 카운터가 등록되어야 한다" }
            assertEquals(1.0, counter!!.count()) { "success 카운터는 1이어야 한다" }
        }

        @Test
        fun `task 완료 실패는 failure 태그와 errorCode를 기록해야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordTaskCompleted(success = false, durationMs = 500L, errorCode = "TIMEOUT")

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TASK_COMPLETED)
                .tag(MicrometerEvaluationMetricsCollector.TAG_RESULT, MicrometerEvaluationMetricsCollector.RESULT_FAILURE)
                .tag(MicrometerEvaluationMetricsCollector.TAG_ERROR_CODE, "TIMEOUT")
                .counter()
            assertNotNull(counter) { "failure TIMEOUT 카운터가 등록되어야 한다" }
            assertEquals(1.0, counter!!.count()) { "failure 카운터는 1이어야 한다" }
        }

        @Test
        fun `task 지속 시간은 Timer로 기록되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordTaskCompleted(success = true, durationMs = 750L)
            collector.recordTaskCompleted(success = true, durationMs = 1_500L)

            val timer = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TASK_DURATION)
                .tag(MicrometerEvaluationMetricsCollector.TAG_RESULT, MicrometerEvaluationMetricsCollector.RESULT_SUCCESS)
                .timer()
            assertNotNull(timer) { "task duration timer가 등록되어야 한다" }
            assertEquals(2L, timer!!.count()) { "timer는 2회 기록되어야 한다" }
        }

        @Test
        fun `도구 호출 수는 DistributionSummary로 기록되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordToolCallCount(count = 3)
            collector.recordToolCallCount(count = 5)
            collector.recordToolCallCount(count = 1)

            val summary = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOOL_CALLS)
                .summary()
            assertNotNull(summary) { "tool calls summary가 등록되어야 한다" }
            assertEquals(3L, summary!!.count()) { "3회 기록되어야 한다" }
            assertEquals(9.0, summary.totalAmount()) { "누적값은 3+5+1=9이어야 한다" }
        }

        @Test
        fun `도구 호출 수가 음수여도 안전하게 0으로 처리되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordToolCallCount(count = -1)

            val summary = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOOL_CALLS).summary()
            assertNotNull(summary) { "summary는 등록되어야 한다" }
            assertEquals(0.0, summary!!.totalAmount()) { "음수 값은 0으로 정규화되어야 한다" }
        }

        @Test
        fun `토큰 비용은 model 태그로 누적되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordTokenCost(costUsd = 0.001, model = "gemini-2.5-flash")
            collector.recordTokenCost(costUsd = 0.002, model = "gemini-2.5-flash")
            collector.recordTokenCost(costUsd = 0.005, model = "claude-opus-4")

            val flashCounter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOKEN_COST)
                .tag(MicrometerEvaluationMetricsCollector.TAG_MODEL, "gemini-2.5-flash")
                .counter()
            val claudeCounter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOKEN_COST)
                .tag(MicrometerEvaluationMetricsCollector.TAG_MODEL, "claude-opus-4")
                .counter()

            assertNotNull(flashCounter) { "gemini flash 비용 카운터가 등록되어야 한다" }
            assertNotNull(claudeCounter) { "claude 비용 카운터가 등록되어야 한다" }
            assertEquals(0.003, flashCounter!!.count(), 0.0001) { "flash 누적은 0.003이어야 한다" }
            assertEquals(0.005, claudeCounter!!.count(), 0.0001) { "claude는 0.005이어야 한다" }
        }

        @Test
        fun `음수 비용은 0으로 정규화되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordTokenCost(costUsd = -10.0, model = "buggy-model")

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOKEN_COST)
                .tag(MicrometerEvaluationMetricsCollector.TAG_MODEL, "buggy-model")
                .counter()
            assertNotNull(counter) { "카운터는 등록되어야 한다" }
            assertEquals(0.0, counter!!.count()) { "음수 비용은 0으로 정규화되어야 한다" }
        }

        @Test
        fun `HITL 개입은 outcome과 tool 태그로 기록되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordHumanOverride(HumanOverrideOutcome.APPROVED, "delete_order")
            collector.recordHumanOverride(HumanOverrideOutcome.REJECTED, "delete_order")
            collector.recordHumanOverride(HumanOverrideOutcome.TIMEOUT, "slow_tool")

            val approved = counterValue(registry, MicrometerEvaluationMetricsCollector.METRIC_HUMAN_OVERRIDE,
                listOf(
                    MicrometerEvaluationMetricsCollector.TAG_OUTCOME to "approved",
                    MicrometerEvaluationMetricsCollector.TAG_TOOL to "delete_order"
                ))
            val rejected = counterValue(registry, MicrometerEvaluationMetricsCollector.METRIC_HUMAN_OVERRIDE,
                listOf(
                    MicrometerEvaluationMetricsCollector.TAG_OUTCOME to "rejected",
                    MicrometerEvaluationMetricsCollector.TAG_TOOL to "delete_order"
                ))
            val timeout = counterValue(registry, MicrometerEvaluationMetricsCollector.METRIC_HUMAN_OVERRIDE,
                listOf(
                    MicrometerEvaluationMetricsCollector.TAG_OUTCOME to "timeout",
                    MicrometerEvaluationMetricsCollector.TAG_TOOL to "slow_tool"
                ))

            assertEquals(1.0, approved) { "approved 카운터는 1이어야 한다" }
            assertEquals(1.0, rejected) { "rejected 카운터는 1이어야 한다" }
            assertEquals(1.0, timeout) { "timeout 카운터는 1이어야 한다" }
        }

        @Test
        fun `안전 거부는 stage와 reason 태그로 기록되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordSafetyRejection(SafetyRejectionStage.GUARD, "injection")
            collector.recordSafetyRejection(SafetyRejectionStage.OUTPUT_GUARD, "pii")
            collector.recordSafetyRejection(SafetyRejectionStage.HOOK, "unauthorized")

            val guardCounter = counterValue(registry, MicrometerEvaluationMetricsCollector.METRIC_SAFETY_REJECTION,
                listOf(
                    MicrometerEvaluationMetricsCollector.TAG_STAGE to "guard",
                    MicrometerEvaluationMetricsCollector.TAG_REASON to "injection"
                ))
            val outputCounter = counterValue(registry, MicrometerEvaluationMetricsCollector.METRIC_SAFETY_REJECTION,
                listOf(
                    MicrometerEvaluationMetricsCollector.TAG_STAGE to "output_guard",
                    MicrometerEvaluationMetricsCollector.TAG_REASON to "pii"
                ))
            val hookCounter = counterValue(registry, MicrometerEvaluationMetricsCollector.METRIC_SAFETY_REJECTION,
                listOf(
                    MicrometerEvaluationMetricsCollector.TAG_STAGE to "hook",
                    MicrometerEvaluationMetricsCollector.TAG_REASON to "unauthorized"
                ))

            assertEquals(1.0, guardCounter) { "guard injection 카운터는 1이어야 한다" }
            assertEquals(1.0, outputCounter) { "output_guard pii 카운터는 1이어야 한다" }
            assertEquals(1.0, hookCounter) { "hook unauthorized 카운터는 1이어야 한다" }
        }

        @Test
        fun `빈 모델 이름은 unknown 태그로 변환되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordTokenCost(costUsd = 0.001, model = "")

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOKEN_COST)
                .tag(MicrometerEvaluationMetricsCollector.TAG_MODEL, MicrometerEvaluationMetricsCollector.UNKNOWN_TAG)
                .counter()
            assertNotNull(counter) { "unknown 태그로 카운터가 등록되어야 한다" }
        }

        @Test
        fun `도구 응답 분류는 kind와 tool 태그로 기록되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordToolResponseKind("list_top_n", "jira_search")
            collector.recordToolResponseKind("list_top_n", "jira_search")
            collector.recordToolResponseKind("error_cause_first", "bitbucket_list_prs")
            collector.recordToolResponseKind("structured", "jira_get_issue")

            val listCounter = counterValue(registry, MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_KIND,
                listOf(
                    MicrometerEvaluationMetricsCollector.TAG_KIND to "list_top_n",
                    MicrometerEvaluationMetricsCollector.TAG_TOOL to "jira_search"
                ))
            val errorCounter = counterValue(registry, MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_KIND,
                listOf(
                    MicrometerEvaluationMetricsCollector.TAG_KIND to "error_cause_first",
                    MicrometerEvaluationMetricsCollector.TAG_TOOL to "bitbucket_list_prs"
                ))
            val structuredCounter = counterValue(registry, MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_KIND,
                listOf(
                    MicrometerEvaluationMetricsCollector.TAG_KIND to "structured",
                    MicrometerEvaluationMetricsCollector.TAG_TOOL to "jira_get_issue"
                ))

            assertEquals(2.0, listCounter) { "list_top_n + jira_search 카운터는 2여야 한다" }
            assertEquals(1.0, errorCounter) { "error_cause_first + bitbucket_list_prs 카운터는 1" }
            assertEquals(1.0, structuredCounter) { "structured + jira_get_issue 카운터는 1" }
        }

        @Test
        fun `빈 kind와 tool은 unknown으로 변환되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordToolResponseKind("", "")

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_KIND)
                .tag(MicrometerEvaluationMetricsCollector.TAG_KIND, MicrometerEvaluationMetricsCollector.UNKNOWN_TAG)
                .tag(MicrometerEvaluationMetricsCollector.TAG_TOOL, MicrometerEvaluationMetricsCollector.UNKNOWN_TAG)
                .counter()
            assertNotNull(counter) { "빈 문자열은 unknown 태그로 등록되어야 한다" }
            assertEquals(1.0, counter!!.count()) { "unknown 카운터는 1" }
        }

        private fun counterValue(
            registry: SimpleMeterRegistry,
            name: String,
            tags: List<Pair<String, String>>
        ): Double {
            val search = tags.fold(registry.find(name)) { s, (k, v) -> s.tag(k, v) }
            val counter: Counter? = search.counter()
            return counter?.count() ?: 0.0
        }
    }

    @Nested
    inner class NoOpToolResponseKind {

        @Test
        fun `NoOp 수집기의 recordToolResponseKind는 no-op이어야 한다`() {
            // 기본 no-op 구현이 예외 없이 실행되어야 한다
            NoOpEvaluationMetricsCollector.recordToolResponseKind("list_top_n", "jira_search")
            NoOpEvaluationMetricsCollector.recordToolResponseKind("", "")
            assertTrue(true) { "NoOp 수집기의 새 메서드는 예외 없이 반환해야 한다" }
        }

        @Test
        fun `기본 구현은 interface default no-op이어야 한다 (backward compat)`() {
            // 사용자가 recordToolResponseKind를 구현하지 않은 커스텀 collector도 작동해야 한다
            val customCollector = object : EvaluationMetricsCollector {
                override fun recordTaskCompleted(success: Boolean, durationMs: Long, errorCode: String?) {}
                override fun recordToolCallCount(count: Int, toolNames: List<String>) {}
                override fun recordTokenCost(costUsd: Double, model: String) {}
                override fun recordHumanOverride(outcome: HumanOverrideOutcome, toolName: String) {}
                override fun recordSafetyRejection(stage: SafetyRejectionStage, reason: String) {}
                // recordToolResponseKind 구현 생략 → default no-op
            }
            // 기본 구현이 있어야 컴파일 + 호출이 가능하다
            customCollector.recordToolResponseKind("list_top_n", "tool")
            assertTrue(true) { "default no-op 구현이 backward compat를 유지해야 한다" }
        }
    }

    @Nested
    inner class ToolResponseCompression {

        private fun newCollector(): Pair<SimpleMeterRegistry, MicrometerEvaluationMetricsCollector> {
            val registry = SimpleMeterRegistry()
            return registry to MicrometerEvaluationMetricsCollector(registry)
        }

        @Test
        fun `압축률은 DistributionSummary로 tool 태그와 함께 기록되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordToolResponseCompression(75, "jira_search")
            collector.recordToolResponseCompression(88, "jira_search")
            collector.recordToolResponseCompression(42, "confluence_get_page")

            val jiraSummary = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_COMPRESSION)
                .tag(MicrometerEvaluationMetricsCollector.TAG_TOOL, "jira_search")
                .summary()
            assertNotNull(jiraSummary) { "jira_search 압축률 DistributionSummary 등록 확인" }
            assertEquals(2L, jiraSummary!!.count()) { "jira_search 2번 기록" }
            assertEquals(163.0, jiraSummary.totalAmount()) { "75 + 88 = 163" }

            val confluenceSummary = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_COMPRESSION)
                .tag(MicrometerEvaluationMetricsCollector.TAG_TOOL, "confluence_get_page")
                .summary()
            assertNotNull(confluenceSummary) { "confluence_get_page DistributionSummary 등록" }
            assertEquals(1L, confluenceSummary!!.count())
            assertEquals(42.0, confluenceSummary.totalAmount())
        }

        @Test
        fun `음수 압축률은 0으로 clamp되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordToolResponseCompression(-50, "weird_tool")

            val summary = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_COMPRESSION)
                .tag(MicrometerEvaluationMetricsCollector.TAG_TOOL, "weird_tool")
                .summary()
            assertNotNull(summary) { "음수도 summary 등록" }
            assertEquals(0.0, summary!!.totalAmount()) {
                "음수는 0으로 clamp되어야 한다"
            }
        }

        @Test
        fun `빈 tool 이름은 unknown 태그로 변환되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordToolResponseCompression(50, "")

            val summary = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_COMPRESSION)
                .tag(MicrometerEvaluationMetricsCollector.TAG_TOOL, MicrometerEvaluationMetricsCollector.UNKNOWN_TAG)
                .summary()
            assertNotNull(summary) { "빈 tool → unknown 태그" }
        }

        @Test
        fun `여러 도구가 독립된 태그로 분리되어야 한다`() {
            val (registry, collector) = newCollector()
            listOf(
                90 to "jira_search",
                85 to "jira_search",
                60 to "confluence_get_page",
                10 to "bitbucket_get_pr"
            ).forEach { (percent, tool) ->
                collector.recordToolResponseCompression(percent, tool)
            }

            val tools = registry.find(MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_COMPRESSION)
                .summaries()
                .mapNotNull { it.id.getTag(MicrometerEvaluationMetricsCollector.TAG_TOOL) }
                .toSet()
            assertEquals(
                setOf("jira_search", "confluence_get_page", "bitbucket_get_pr"),
                tools
            ) { "3개 독립 tool 태그가 생성되어야 한다" }
        }

        @Test
        fun `NoOp 수집기의 recordToolResponseCompression는 no-op이어야 한다`() {
            NoOpEvaluationMetricsCollector.recordToolResponseCompression(75, "jira_search")
            NoOpEvaluationMetricsCollector.recordToolResponseCompression(-100, "")
            assertTrue(true) { "NoOp 수집기의 새 메서드는 예외 없이 반환해야 한다" }
        }

        @Test
        fun `interface default 구현은 no-op이어야 한다 (backward compat)`() {
            val customCollector = object : EvaluationMetricsCollector {
                override fun recordTaskCompleted(success: Boolean, durationMs: Long, errorCode: String?) {}
                override fun recordToolCallCount(count: Int, toolNames: List<String>) {}
                override fun recordTokenCost(costUsd: Double, model: String) {}
                override fun recordHumanOverride(outcome: HumanOverrideOutcome, toolName: String) {}
                override fun recordSafetyRejection(stage: SafetyRejectionStage, reason: String) {}
                // recordToolResponseKind + recordToolResponseCompression 구현 생략 → default no-op
            }
            customCollector.recordToolResponseCompression(75, "tool")
            assertTrue(true) { "default no-op 구현이 backward compat를 유지해야 한다" }
        }
    }

    @Nested
    inner class ExecutionError {

        private fun newCollector(): Pair<SimpleMeterRegistry, MicrometerEvaluationMetricsCollector> {
            val registry = SimpleMeterRegistry()
            return registry to MicrometerEvaluationMetricsCollector(registry)
        }

        @Test
        fun `실행 에러는 stage와 exception 태그로 Counter 기록되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordExecutionError(ExecutionStage.TOOL_CALL, "SocketTimeoutException")
            collector.recordExecutionError(ExecutionStage.TOOL_CALL, "SocketTimeoutException")
            collector.recordExecutionError(ExecutionStage.LLM_CALL, "HttpClientErrorException")
            collector.recordExecutionError(ExecutionStage.PARSING, "JsonParseException")

            val toolTimeout = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "tool_call")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "SocketTimeoutException")
                .counter()
            assertNotNull(toolTimeout) { "tool_call + SocketTimeoutException Counter 등록" }
            assertEquals(2.0, toolTimeout!!.count()) { "2회 기록" }

            val llmError = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "llm_call")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "HttpClientErrorException")
                .counter()
            assertNotNull(llmError)
            assertEquals(1.0, llmError!!.count())

            val parseError = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "parsing")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "JsonParseException")
                .counter()
            assertNotNull(parseError)
            assertEquals(1.0, parseError!!.count())
        }

        @Test
        fun `빈 exception 이름은 unknown으로 변환되어야 한다`() {
            val (registry, collector) = newCollector()
            collector.recordExecutionError(ExecutionStage.OTHER, "")

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "other")
                .tag(
                    MicrometerEvaluationMetricsCollector.TAG_EXCEPTION,
                    MicrometerEvaluationMetricsCollector.UNKNOWN_TAG
                )
                .counter()
            assertNotNull(counter) { "빈 exception → unknown 태그" }
            assertEquals(1.0, counter!!.count())
        }

        @Test
        fun `9개 ExecutionStage 모두 독립적으로 기록되어야 한다`() {
            val (registry, collector) = newCollector()
            ExecutionStage.values().forEach { stage ->
                collector.recordExecutionError(stage, "TestException")
            }

            val stages = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counters()
                .mapNotNull { it.id.getTag(MicrometerEvaluationMetricsCollector.TAG_STAGE) }
                .toSet()
            assertEquals(9, stages.size) {
                "9개 ExecutionStage가 독립 태그로 등록되어야 한다"
            }
        }

        @Test
        fun `NoOp 수집기의 recordExecutionError는 no-op이어야 한다`() {
            NoOpEvaluationMetricsCollector.recordExecutionError(
                ExecutionStage.TOOL_CALL,
                "TimeoutException"
            )
            NoOpEvaluationMetricsCollector.recordExecutionError(ExecutionStage.OTHER, "")
            assertTrue(true) { "NoOp 수집기의 새 메서드는 예외 없이 반환해야 한다" }
        }

        @Test
        fun `interface default 구현은 no-op이어야 한다 (backward compat)`() {
            val customCollector = object : EvaluationMetricsCollector {
                override fun recordTaskCompleted(success: Boolean, durationMs: Long, errorCode: String?) {}
                override fun recordToolCallCount(count: Int, toolNames: List<String>) {}
                override fun recordTokenCost(costUsd: Double, model: String) {}
                override fun recordHumanOverride(outcome: HumanOverrideOutcome, toolName: String) {}
                override fun recordSafetyRejection(stage: SafetyRejectionStage, reason: String) {}
                // recordExecutionError 구현 생략 → default no-op
            }
            customCollector.recordExecutionError(ExecutionStage.LLM_CALL, "AnyException")
            assertTrue(true) { "default no-op 구현이 backward compat를 유지해야 한다" }
        }

        @Test
        fun `ExecutionStage enum은 9개 값을 가져야 한다`() {
            assertEquals(9, ExecutionStage.values().size) {
                "TOOL_CALL/LLM_CALL/GUARD/HOOK/OUTPUT_GUARD/PARSING/MEMORY/CACHE/OTHER"
            }
        }
    }

    @Nested
    inner class RecordErrorExtension {

        private fun newCollector(): Pair<SimpleMeterRegistry, MicrometerEvaluationMetricsCollector> {
            val registry = SimpleMeterRegistry()
            return registry to MicrometerEvaluationMetricsCollector(registry)
        }

        @Test
        fun `recordError 확장 함수는 Throwable의 simpleName을 추출해 기록해야 한다`() {
            val (registry, collector) = newCollector()
            val exception = IllegalStateException("oops")
            collector.recordError(ExecutionStage.TOOL_CALL, exception)

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "tool_call")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalStateException")
                .counter()
            assertNotNull(counter)
            assertEquals(1.0, counter!!.count())
        }

        @Test
        fun `recordError는 RuntimeException 계열도 정상 처리해야 한다`() {
            val (registry, collector) = newCollector()
            listOf<Throwable>(
                RuntimeException("a"),
                IllegalArgumentException("b"),
                NullPointerException("c"),
                UnsupportedOperationException("d")
            ).forEach { collector.recordError(ExecutionStage.OTHER, it) }

            val classes = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counters()
                .mapNotNull { it.id.getTag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION) }
                .toSet()
            assertEquals(
                setOf(
                    "RuntimeException",
                    "IllegalArgumentException",
                    "NullPointerException",
                    "UnsupportedOperationException"
                ),
                classes
            ) { "4개 예외 클래스 simpleName이 각각 독립 태그로 기록" }
        }

        @Test
        fun `recordError는 익명 inner 클래스 예외도 처리해야 한다`() {
            val (registry, collector) = newCollector()
            // 익명 inner class: javaClass.simpleName이 빈 문자열이 될 수 있음
            val anonymous: Throwable = object : RuntimeException("anon") {}
            collector.recordError(ExecutionStage.OTHER, anonymous)

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counters()
                .firstOrNull()
            assertNotNull(counter) {
                "익명 클래스 예외도 Counter가 등록되어야 한다"
            }
            val exceptionTag = counter!!.id.getTag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION)
            assertNotNull(exceptionTag) { "exception 태그가 반드시 존재해야 한다" }
            assertTrue(exceptionTag!!.isNotBlank()) {
                "빈 simpleName → fully-qualified 마지막 세그먼트 또는 UnknownException fallback: $exceptionTag"
            }
        }

        @Test
        fun `recordError는 NoOp 수집기에서도 no-op이어야 한다`() {
            NoOpEvaluationMetricsCollector.recordError(
                ExecutionStage.TOOL_CALL,
                IllegalStateException("test")
            )
            assertTrue(true) { "예외 없이 반환" }
        }

        @Test
        fun `recordError는 9개 stage 모두 사용 가능해야 한다`() {
            val (registry, collector) = newCollector()
            ExecutionStage.values().forEach { stage ->
                collector.recordError(stage, RuntimeException("x"))
            }

            val stages = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counters()
                .mapNotNull { it.id.getTag(MicrometerEvaluationMetricsCollector.TAG_STAGE) }
                .toSet()
            assertEquals(9, stages.size) {
                "9개 stage 모두 독립 태그"
            }
        }
    }
}
