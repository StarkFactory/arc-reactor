package com.arc.reactor.hook

import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.MicrometerEvaluationMetricsCollector
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * HookExecutor에 대한 테스트.
 *
 * 훅 실행기의 순서 보장과 오류 처리를 검증합니다.
 */
class HookExecutorTest {

    @Nested
    inner class BeforeAgentStartHooks {

        @Test
        fun `hooks은(는) execute in ascending order`() = runBlocking {
            val executionOrder = mutableListOf<Int>()

            val hook1 = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    executionOrder.add(1)
                    return HookResult.Continue
                }
            }
            val hook2 = object : BeforeAgentStartHook {
                override val order = 2
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    executionOrder.add(2)
                    return HookResult.Continue
                }
            }

            val executor = HookExecutor(
                beforeStartHooks = listOf(hook2, hook1) // reverse order
            )

            executor.executeBeforeAgentStart(
                HookContext(runId = "run-1", userId = "user-1", userPrompt = "Hello")
            )

            assertEquals(listOf(1, 2), executionOrder)
        }

        @Test
        fun `and returns Reject when a hook rejects를 중지한다`() = runBlocking {
            val executionOrder = mutableListOf<Int>()

            val hook1 = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    executionOrder.add(1)
                    return HookResult.Continue
                }
            }
            val hook2 = object : BeforeAgentStartHook {
                override val order = 2
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    executionOrder.add(2)
                    return HookResult.Reject("Permission denied")
                }
            }
            val hook3 = object : BeforeAgentStartHook {
                override val order = 3
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    executionOrder.add(3)
                    return HookResult.Continue
                }
            }

            val executor = HookExecutor(
                beforeStartHooks = listOf(hook1, hook2, hook3)
            )

            val result = executor.executeBeforeAgentStart(
                HookContext(runId = "run-1", userId = "user-1", userPrompt = "Hello")
            )

            assertInstanceOf(HookResult.Reject::class.java, result)
            assertEquals(listOf(1, 2), executionOrder, "Hook 3 should not execute after rejection")
        }

        @Test
        fun `hook has failOnError true일 때 fail-close`() = runBlocking {
            val hook = object : BeforeAgentStartHook {
                override val order = 1
                override val failOnError = true
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    throw RuntimeException("Critical hook failed")
                }
            }

            val executor = HookExecutor(beforeStartHooks = listOf(hook))

            val result = executor.executeBeforeAgentStart(
                HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            )

            val reject = assertInstanceOf(HookResult.Reject::class.java, result)
            assertTrue(reject.reason.contains("내부 처리 중 오류가 발생했습니다")) {
                "Reject reason은 한글 에러 메시지여야 한다, got: ${reject.reason}"
            }
        }

        @Test
        fun `hook has failOnError false일 때 fail-open`() = runBlocking {
            val executionOrder = mutableListOf<Int>()

            val failingHook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    throw RuntimeException("Non-critical hook failed")
                }
            }
            val successHook = object : BeforeAgentStartHook {
                override val order = 2
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    executionOrder.add(2)
                    return HookResult.Continue
                }
            }

            val executor = HookExecutor(beforeStartHooks = listOf(failingHook, successHook))

            val result = executor.executeBeforeAgentStart(
                HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
            )

            assertInstanceOf(HookResult.Continue::class.java, result)
            assertEquals(listOf(2), executionOrder, "Second hook should still execute")
        }

        @Test
        fun `rethrow cancellation exception from hook해야 한다`() {
            val hook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    throw CancellationException("cancelled")
                }
            }
            val executor = HookExecutor(beforeStartHooks = listOf(hook))

            assertThrows(CancellationException::class.java) {
                runBlocking {
                    executor.executeBeforeAgentStart(
                        HookContext(runId = "run-1", userId = "user-1", userPrompt = "cancel me")
                    )
                }
            }
        }
    }

    @Nested
    inner class ToolCallHooks {

        @Test
        fun `BeforeToolCallHook은(는) correct tool name를 수신한다`() = runBlocking {
            val capturedToolNames = mutableListOf<String>()

            val hook = object : BeforeToolCallHook {
                override val order = 1
                override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
                    capturedToolNames.add(context.toolName)
                    return HookResult.Continue
                }
            }

            val executor = HookExecutor(beforeToolCallHooks = listOf(hook))

            executor.executeBeforeToolCall(ToolCallContext(
                agentContext = HookContext(runId = "run-1", userId = "user-1", userPrompt = "Hello"),
                toolName = "search_database",
                toolParams = mapOf("query" to "test"),
                callIndex = 0
            ))

            assertEquals(listOf("search_database"), capturedToolNames)
        }

        @Test
        fun `AfterToolCallHook은(는) tool result를 수신한다`() = runBlocking {
            val capturedResults = mutableListOf<ToolCallResult>()

            val hook = object : AfterToolCallHook {
                override val order = 1
                override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                    capturedResults.add(result)
                }
            }

            val executor = HookExecutor(afterToolCallHooks = listOf(hook))

            executor.executeAfterToolCall(
                ToolCallContext(
                    agentContext = HookContext(runId = "run-1", userId = "user-1", userPrompt = "Hello"),
                    toolName = "calculator",
                    toolParams = mapOf("a" to 1, "b" to 2),
                    callIndex = 0
                ),
                ToolCallResult(success = true, output = "3", durationMs = 100)
            )

            assertEquals(1, capturedResults.size)
            assertEquals("3", capturedResults[0].output)
        }
    }

    @Nested
    inner class AfterAgentCompleteHooks {

        @Test
        fun `receives은(는) agent response correctly`() = runBlocking {
            val capturedResponses = mutableListOf<AgentResponse>()

            val hook = object : AfterAgentCompleteHook {
                override val order = 1
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    capturedResponses.add(response)
                }
            }

            val executor = HookExecutor(afterCompleteHooks = listOf(hook))

            executor.executeAfterAgentComplete(
                HookContext(runId = "run-1", userId = "user-1", userPrompt = "Hello"),
                AgentResponse(success = true, response = "Hello! How can I help you?", toolsUsed = listOf("greeting_tool"))
            )

            assertEquals(1, capturedResponses.size)
            assertEquals("Hello! How can I help you?", capturedResponses[0].response)
        }
    }

    @Nested
    inner class SensitiveParamMasking {

        @Test
        fun `sensitive parameters in ToolCallContext를 마스킹한다`() {
            val toolContext = ToolCallContext(
                agentContext = HookContext(runId = "run-1", userId = "user-1", userPrompt = "Test"),
                toolName = "api_call",
                toolParams = mapOf(
                    "url" to "https://api.example.com",
                    "apikey" to "secret123",
                    "password" to "password123",
                    "data" to "normal data"
                ),
                callIndex = 0
            )

            val masked = toolContext.maskedParams()

            assertEquals("https://api.example.com", masked["url"])
            assertEquals("***", masked["apikey"])
            assertEquals("***", masked["password"])
            assertEquals("normal data", masked["data"])
        }
    }

    // ========================================================================
    // R249: HOOK stage 자동 기록 테스트
    // ========================================================================

    @Nested
    inner class R249ExecutionErrorRecording {

        private fun newCollector(): Pair<SimpleMeterRegistry, EvaluationMetricsCollector> {
            val registry = SimpleMeterRegistry()
            return registry to MicrometerEvaluationMetricsCollector(registry)
        }

        @Test
        fun `R249 Before Hook 예외가 HOOK stage로 기록되어야 한다 (fail-open)`() = runBlocking {
            val (registry, collector) = newCollector()
            val failingHook = object : BeforeAgentStartHook {
                override val order = 1
                override val failOnError = false
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    throw IllegalStateException("hook boom")
                }
            }
            val executor = HookExecutor(
                beforeStartHooks = listOf(failingHook),
                evaluationMetricsCollector = collector
            )

            val result = executor.executeBeforeAgentStart(
                HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            )

            // fail-open → Continue 반환
            assertEquals(HookResult.Continue, result)

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "hook")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalStateException")
                .counter()
            assertNotNull(counter) {
                "Before Hook 예외도 HOOK stage로 기록되어야 한다"
            }
            assertEquals(1.0, counter!!.count())
        }

        @Test
        fun `R249 BeforeToolCall Hook 예외도 HOOK stage로 기록`() = runBlocking {
            val (registry, collector) = newCollector()
            val failingHook = object : BeforeToolCallHook {
                override val order = 1
                override val failOnError = false
                override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
                    throw IllegalArgumentException("bad tool")
                }
            }
            val executor = HookExecutor(
                beforeToolCallHooks = listOf(failingHook),
                evaluationMetricsCollector = collector
            )

            executor.executeBeforeToolCall(
                ToolCallContext(
                    agentContext = HookContext(runId = "run-1", userId = "u", userPrompt = "p"),
                    toolName = "test_tool",
                    toolParams = emptyMap(),
                    callIndex = 0
                )
            )

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "hook")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalArgumentException")
                .counter()
            assertNotNull(counter) { "BeforeToolCall Hook 예외도 기록" }
            assertEquals(1.0, counter!!.count())
        }

        @Test
        fun `R249 AfterToolCall Hook 예외가 HOOK stage로 기록되어야 한다`() = runBlocking {
            val (registry, collector) = newCollector()
            val failingHook = object : AfterToolCallHook {
                override val order = 1
                override val failOnError = false
                override suspend fun afterToolCall(
                    context: ToolCallContext,
                    result: ToolCallResult
                ) {
                    throw NullPointerException("after hook NPE")
                }
            }
            val executor = HookExecutor(
                afterToolCallHooks = listOf(failingHook),
                evaluationMetricsCollector = collector
            )

            executor.executeAfterToolCall(
                ToolCallContext(
                    agentContext = HookContext(runId = "run-1", userId = "u", userPrompt = "p"),
                    toolName = "test_tool",
                    toolParams = emptyMap(),
                    callIndex = 0
                ),
                ToolCallResult(success = true, output = "ok", durationMs = 10L)
            )

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "hook")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "NullPointerException")
                .counter()
            assertNotNull(counter) { "AfterToolCall Hook 예외 기록" }
            assertEquals(1.0, counter!!.count())
        }

        @Test
        fun `R249 AfterAgentComplete Hook 예외가 HOOK stage로 기록되어야 한다`() = runBlocking {
            val (registry, collector) = newCollector()
            val failingHook = object : AfterAgentCompleteHook {
                override val order = 1
                override val failOnError = false
                override suspend fun afterAgentComplete(
                    context: HookContext,
                    response: AgentResponse
                ) {
                    throw RuntimeException("after complete boom")
                }
            }
            val executor = HookExecutor(
                afterCompleteHooks = listOf(failingHook),
                evaluationMetricsCollector = collector
            )

            executor.executeAfterAgentComplete(
                HookContext(runId = "run-1", userId = "u", userPrompt = "p"),
                AgentResponse(success = true, totalDurationMs = 100L)
            )

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "hook")
                .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "RuntimeException")
                .counter()
            assertNotNull(counter) { "AfterAgentComplete Hook 예외 기록" }
            assertEquals(1.0, counter!!.count())
        }

        @Test
        fun `R249 failOnError=true인 Hook 예외도 기록되고 재throw되어야 한다`() = runBlocking {
            val (registry, collector) = newCollector()
            val failingHook = object : AfterToolCallHook {
                override val order = 1
                override val failOnError = true  // fail-close
                override suspend fun afterToolCall(
                    context: ToolCallContext,
                    result: ToolCallResult
                ) {
                    throw IllegalStateException("critical hook failure")
                }
            }
            val executor = HookExecutor(
                afterToolCallHooks = listOf(failingHook),
                evaluationMetricsCollector = collector
            )

            try {
                executor.executeAfterToolCall(
                    ToolCallContext(
                        agentContext = HookContext(runId = "run-1", userId = "u", userPrompt = "p"),
                        toolName = "test_tool",
                        toolParams = emptyMap(),
                        callIndex = 0
                    ),
                    ToolCallResult(success = true, output = "ok", durationMs = 10L)
                )
                fail("Expected IllegalStateException to be rethrown")
            } catch (_: IllegalStateException) {
                // 예상
            }

            val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "hook")
                .counter()
            assertNotNull(counter) {
                "failOnError=true 경우에도 재throw 전에 기록되어야 한다"
            }
            assertEquals(1.0, counter!!.count())
        }

        @Test
        fun `R249 CancellationException은 기록하지 않고 재throw`() = runBlocking {
            val (registry, collector) = newCollector()
            val cancellingHook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    throw CancellationException("cancelled")
                }
            }
            val executor = HookExecutor(
                beforeStartHooks = listOf(cancellingHook),
                evaluationMetricsCollector = collector
            )

            try {
                executor.executeBeforeAgentStart(
                    HookContext(runId = "run-1", userId = "u", userPrompt = "p")
                )
                fail("Expected CancellationException")
            } catch (_: CancellationException) {
                // 예상
            }

            val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counter()
            assertNull(meter) {
                "CancellationException은 execution.error에 기록하지 않아야 한다"
            }
        }

        @Test
        fun `R249 성공한 Hook은 기록하지 않음`() = runBlocking {
            val (registry, collector) = newCollector()
            val healthyHook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    return HookResult.Continue
                }
            }
            val executor = HookExecutor(
                beforeStartHooks = listOf(healthyHook),
                evaluationMetricsCollector = collector
            )

            executor.executeBeforeAgentStart(
                HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            )

            val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counter()
            assertNull(meter) { "성공한 Hook은 카운터 생성 안 됨" }
        }

        @Test
        fun `R249 기본 NoOp collector로 backward compat 유지`() = runBlocking {
            // evaluationMetricsCollector 파라미터 생략 → NoOp 기본값
            val failingHook = object : BeforeAgentStartHook {
                override val order = 1
                override val failOnError = false
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    throw RuntimeException("no collector")
                }
            }
            val executor = HookExecutor(beforeStartHooks = listOf(failingHook))

            val result = executor.executeBeforeAgentStart(
                HookContext(runId = "run-1", userId = "u", userPrompt = "p")
            )

            assertEquals(HookResult.Continue, result) {
                "NoOp collector에서도 fail-open 동작은 그대로"
            }
        }
    }
}
