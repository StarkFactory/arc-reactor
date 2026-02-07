package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HookExecutorTest {

    @Nested
    inner class BeforeAgentStartHooks {

        @Test
        fun `hooks execute in ascending order`() = runBlocking {
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
        fun `stops and returns Reject when a hook rejects`() = runBlocking {
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
        fun `PendingApproval result is returned correctly`() = runBlocking {
            val hook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    return HookResult.PendingApproval(
                        approvalId = "approval-123",
                        message = "Requires manager approval"
                    )
                }
            }

            val executor = HookExecutor(beforeStartHooks = listOf(hook))

            val result = executor.executeBeforeAgentStart(
                HookContext(runId = "run-1", userId = "user-1", userPrompt = "Delete all data")
            )

            val pending = assertInstanceOf(HookResult.PendingApproval::class.java, result)
            assertEquals("Requires manager approval", pending.message)
        }

        @Test
        fun `fail-close when hook has failOnError true`() = runBlocking {
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
            assertTrue(reject.reason.contains("Critical hook failed"),
                "Reject reason should contain original error, got: ${reject.reason}")
        }

        @Test
        fun `fail-open when hook has failOnError false`() = runBlocking {
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
    }

    @Nested
    inner class ToolCallHooks {

        @Test
        fun `BeforeToolCallHook receives correct tool name`() = runBlocking {
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
        fun `AfterToolCallHook receives tool result`() = runBlocking {
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
        fun `receives agent response correctly`() = runBlocking {
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
        fun `masks sensitive parameters in ToolCallContext`() {
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
