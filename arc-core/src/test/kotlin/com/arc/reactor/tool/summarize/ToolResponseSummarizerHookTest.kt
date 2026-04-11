package com.arc.reactor.tool.summarize

import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [ToolResponseSummarizerHook] 단위 테스트.
 *
 * Hook이 ToolCallContext/ToolCallResult를 summarizer로 전달하고 결과를
 * HookContext.metadata에 올바르게 저장하는지 검증한다.
 */
class ToolResponseSummarizerHookTest {

    private fun newContext(callIndex: Int = 0, toolName: String = "jira_search"): ToolCallContext {
        val agentContext = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "test"
        )
        return ToolCallContext(
            agentContext = agentContext,
            toolName = toolName,
            toolParams = emptyMap(),
            callIndex = callIndex
        )
    }

    @Nested
    inner class BasicRecording {

        @Test
        fun `요약기가 null을 반환하면 메타데이터에 저장하지 않아야 한다`() = runTest {
            val hook = ToolResponseSummarizerHook(NoOpToolResponseSummarizer)
            val context = newContext()
            val result = ToolCallResult(success = true, output = "some output", durationMs = 100L)

            hook.afterToolCall(context, result)

            val key = ToolResponseSummarizerHook.buildKey(0, "jira_search")
            assertNull(context.agentContext.metadata[key]) {
                "NoOp 요약기는 메타데이터에 기록을 남기지 않아야 한다"
            }
            assertNull(context.agentContext.metadata[ToolResponseSummarizerHook.COUNTER_KEY]) {
                "카운터도 증가되지 않아야 한다"
            }
        }

        @Test
        fun `요약 결과가 있으면 메타데이터에 저장되어야 한다`() = runTest {
            val hook = ToolResponseSummarizerHook(DefaultToolResponseSummarizer())
            val context = newContext(callIndex = 0, toolName = "jira_search")
            val payload = """[{"key":"JAR-1"},{"key":"JAR-2"}]"""
            val result = ToolCallResult(success = true, output = payload, durationMs = 100L)

            hook.afterToolCall(context, result)

            val key = ToolResponseSummarizerHook.buildKey(0, "jira_search")
            val stored = context.agentContext.metadata[key]
            assertNotNull(stored) { "요약이 메타데이터에 저장되어야 한다" }
            assertTrue(stored is ToolResponseSummary) {
                "저장된 값은 ToolResponseSummary 타입이어야 한다"
            }
            val summary = stored as ToolResponseSummary
            assertEquals(SummaryKind.LIST_TOP_N, summary.kind) { "배열은 LIST_TOP_N" }
            assertEquals(2, summary.itemCount) { "항목 수 정확" }
        }

        @Test
        fun `카운터가 요약 생성마다 증가해야 한다`() = runTest {
            val hook = ToolResponseSummarizerHook(DefaultToolResponseSummarizer())
            val agentContext = HookContext(runId = "run-1", userId = "u", userPrompt = "p")

            suspend fun invoke(callIndex: Int, toolName: String, payload: String) {
                val ctx = ToolCallContext(
                    agentContext = agentContext,
                    toolName = toolName,
                    toolParams = emptyMap(),
                    callIndex = callIndex
                )
                hook.afterToolCall(ctx, ToolCallResult(success = true, output = payload, durationMs = 1L))
            }

            invoke(0, "jira_search", """[{"key":"A-1"}]""")
            invoke(1, "confluence_search", """[{"key":"DOC-1"}]""")
            invoke(2, "bitbucket_list_prs", """[{"id":42}]""")

            val counter = agentContext.metadata[ToolResponseSummarizerHook.COUNTER_KEY]
            assertEquals(3, counter) { "3번 호출 후 카운터는 3이어야 한다" }
            assertNotNull(agentContext.metadata[ToolResponseSummarizerHook.buildKey(0, "jira_search")]) {
                "0번 호출 요약 존재"
            }
            assertNotNull(agentContext.metadata[ToolResponseSummarizerHook.buildKey(1, "confluence_search")]) {
                "1번 호출 요약 존재"
            }
            assertNotNull(agentContext.metadata[ToolResponseSummarizerHook.buildKey(2, "bitbucket_list_prs")]) {
                "2번 호출 요약 존재"
            }
        }

        /**
         * R335 regression: 병렬 tool call 환경에서 카운터 read-modify-write가 원자적으로
         * 수행되는지 검증. 이전 구현은 non-atomic 3-step 이라 두 코루틴이 동시에
         * `current=0`을 읽고 각자 `1`을 쓰면 최종 카운터가 1(실제 2회 호출인데)이 되는
         * 언더카운트 버그가 있었다.
         *
         * `runBlocking + Dispatchers.Default + async/awaitAll`로 다중 스레드 병렬 실행을
         * 강제한다. `runTest` 단일 쓰레드로는 race를 재현할 수 없다.
         */
        @Test
        fun `R335 병렬 호출에서도 카운터가 정확한 횟수만큼 증가해야 한다`() {
            val hook = ToolResponseSummarizerHook(DefaultToolResponseSummarizer())
            val agentContext = HookContext(runId = "run-parallel", userId = "u", userPrompt = "p")
            val callCount = 200

            runBlocking {
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        (0 until callCount).map { i ->
                            async {
                                val ctx = ToolCallContext(
                                    agentContext = agentContext,
                                    toolName = "jira_search_$i",
                                    toolParams = emptyMap(),
                                    callIndex = i
                                )
                                hook.afterToolCall(
                                    ctx,
                                    ToolCallResult(
                                        success = true,
                                        output = """[{"key":"A-$i"}]""",
                                        durationMs = 1L
                                    )
                                )
                            }
                        }.awaitAll()
                    }
                }
            }

            val counter = agentContext.metadata[ToolResponseSummarizerHook.COUNTER_KEY] as? Int
            assertEquals(
                callCount,
                counter,
                "R335: $callCount 회 병렬 afterToolCall 후 카운터는 정확히 $callCount 이어야 한다 " +
                    "(non-atomic RMW면 언더카운트 발생). 실제=$counter"
            )
        }

        @Test
        fun `동일 도구의 여러 호출도 callIndex로 충돌 없이 저장되어야 한다`() = runTest {
            val hook = ToolResponseSummarizerHook(DefaultToolResponseSummarizer())
            val agentContext = HookContext(runId = "run-1", userId = "u", userPrompt = "p")

            suspend fun invokeIndex(callIndex: Int, payload: String) {
                val ctx = ToolCallContext(
                    agentContext = agentContext,
                    toolName = "jira_search",
                    toolParams = emptyMap(),
                    callIndex = callIndex
                )
                hook.afterToolCall(ctx, ToolCallResult(success = true, output = payload, durationMs = 1L))
            }

            invokeIndex(0, """[{"key":"FIRST"}]""")
            invokeIndex(1, """[{"key":"SECOND"}]""")

            val first = agentContext.metadata[ToolResponseSummarizerHook.buildKey(0, "jira_search")] as? ToolResponseSummary
            val second = agentContext.metadata[ToolResponseSummarizerHook.buildKey(1, "jira_search")] as? ToolResponseSummary

            assertNotNull(first) { "0번 호출 요약 존재" }
            assertNotNull(second) { "1번 호출 요약 존재" }
            assertEquals("FIRST", first!!.primaryKey) { "0번 primary" }
            assertEquals("SECOND", second!!.primaryKey) { "1번 primary" }
        }
    }

    @Nested
    inner class ErrorPayload {

        @Test
        fun `실패 결과는 ERROR_CAUSE_FIRST로 저장되어야 한다`() = runTest {
            val hook = ToolResponseSummarizerHook(DefaultToolResponseSummarizer())
            val context = newContext(toolName = "bitbucket_list_prs")
            val result = ToolCallResult(
                success = false,
                output = "Error: Repository not found",
                errorMessage = "Error: Repository not found",
                durationMs = 50L
            )

            hook.afterToolCall(context, result)

            val stored = context.agentContext.metadata[
                ToolResponseSummarizerHook.buildKey(0, "bitbucket_list_prs")
            ] as? ToolResponseSummary
            assertNotNull(stored) { "에러도 요약되어야 한다" }
            assertEquals(SummaryKind.ERROR_CAUSE_FIRST, stored!!.kind)
            assertTrue(stored.text.startsWith("에러:")) { "에러 텍스트 형식" }
        }

        @Test
        fun `output이 null이면 요약하지 않아야 한다`() = runTest {
            val hook = ToolResponseSummarizerHook(DefaultToolResponseSummarizer())
            val context = newContext()
            val result = ToolCallResult(success = true, output = null, durationMs = 100L)

            hook.afterToolCall(context, result)

            assertTrue(context.agentContext.metadata.isEmpty()) {
                "null output은 요약하지 않으므로 메타데이터가 비어있어야 한다"
            }
        }
    }

    @Nested
    inner class FailOpen {

        @Test
        fun `요약기 예외는 Hook을 통과하지 않아야 한다`() = runTest {
            val brokenSummarizer = mockk<ToolResponseSummarizer>()
            every { brokenSummarizer.summarize(any(), any(), any()) } throws
                RuntimeException("summarizer exploded")

            val hook = ToolResponseSummarizerHook(brokenSummarizer)
            val context = newContext()
            val result = ToolCallResult(success = true, output = "data", durationMs = 100L)

            // 예외가 밖으로 전파되지 않아야 한다
            hook.afterToolCall(context, result)

            assertTrue(context.agentContext.metadata.isEmpty()) {
                "예외 후 메타데이터에 저장되지 않아야 한다"
            }
        }

        @Test
        fun `failOnError는 false여야 한다`() {
            val hook = ToolResponseSummarizerHook(NoOpToolResponseSummarizer)
            assertTrue(!hook.failOnError) { "fail-open이어야 한다" }
        }

        @Test
        fun `Hook order는 160이어야 한다`() {
            val hook = ToolResponseSummarizerHook(NoOpToolResponseSummarizer)
            assertEquals(160, hook.order) { "표준 Hook 범위 (100-199) 내 160" }
        }
    }

    @Nested
    inner class KeyGeneration {

        @Test
        fun `buildKey는 접두사와 callIndex와 toolName을 조합해야 한다`() {
            assertEquals(
                "toolSummary_0_jira_search",
                ToolResponseSummarizerHook.buildKey(0, "jira_search")
            ) { "callIndex=0" }

            assertEquals(
                "toolSummary_5_confluence_search_by_text",
                ToolResponseSummarizerHook.buildKey(5, "confluence_search_by_text")
            ) { "callIndex=5" }
        }

        @Test
        fun `접두사 상수는 toolSummary_이어야 한다`() {
            assertEquals("toolSummary_", ToolResponseSummarizerHook.SUMMARY_KEY_PREFIX)
        }

        @Test
        fun `카운터 키는 toolSummaryCount이어야 한다`() {
            assertEquals("toolSummaryCount", ToolResponseSummarizerHook.COUNTER_KEY)
        }
    }
}
