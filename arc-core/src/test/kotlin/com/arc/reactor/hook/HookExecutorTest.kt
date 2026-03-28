package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
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
}
