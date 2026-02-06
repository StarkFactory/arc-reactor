package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class HookExecutorTest {

    @Test
    fun `should execute before agent start hooks in order`() = runBlocking {
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
            beforeStartHooks = listOf(hook2, hook1), // Add in reverse order
            beforeToolCallHooks = emptyList(),
            afterToolCallHooks = emptyList(),
            afterCompleteHooks = emptyList()
        )

        val context = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "Hello"
        )

        executor.executeBeforeAgentStart(context)

        assertEquals(listOf(1, 2), executionOrder)
    }

    @Test
    fun `should stop on reject result`() = runBlocking {
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
            beforeStartHooks = listOf(hook1, hook2, hook3),
            beforeToolCallHooks = emptyList(),
            afterToolCallHooks = emptyList(),
            afterCompleteHooks = emptyList()
        )

        val result = executor.executeBeforeAgentStart(
            HookContext(runId = "run-1", userId = "user-1", userPrompt = "Hello")
        )

        assertTrue(result is HookResult.Reject)
        assertEquals(listOf(1, 2), executionOrder) // Hook 3 should not execute
    }

    @Test
    fun `should execute before tool call hooks`() = runBlocking {
        val capturedToolNames = mutableListOf<String>()

        val hook = object : BeforeToolCallHook {
            override val order = 1
            override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
                capturedToolNames.add(context.toolName)
                return HookResult.Continue
            }
        }

        val executor = HookExecutor(
            beforeStartHooks = emptyList(),
            beforeToolCallHooks = listOf(hook),
            afterToolCallHooks = emptyList(),
            afterCompleteHooks = emptyList()
        )

        val agentContext = HookContext(runId = "run-1", userId = "user-1", userPrompt = "Hello")
        val toolContext = ToolCallContext(
            agentContext = agentContext,
            toolName = "search_database",
            toolParams = mapOf("query" to "test"),
            callIndex = 0
        )

        executor.executeBeforeToolCall(toolContext)

        assertEquals(listOf("search_database"), capturedToolNames)
    }

    @Test
    fun `should execute after tool call hooks with result`() = runBlocking {
        val capturedResults = mutableListOf<ToolCallResult>()

        val hook = object : AfterToolCallHook {
            override val order = 1
            override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                capturedResults.add(result)
            }
        }

        val executor = HookExecutor(
            beforeStartHooks = emptyList(),
            beforeToolCallHooks = emptyList(),
            afterToolCallHooks = listOf(hook),
            afterCompleteHooks = emptyList()
        )

        val agentContext = HookContext(runId = "run-1", userId = "user-1", userPrompt = "Hello")
        val toolContext = ToolCallContext(
            agentContext = agentContext,
            toolName = "calculator",
            toolParams = mapOf("a" to 1, "b" to 2),
            callIndex = 0
        )
        val toolResult = ToolCallResult(success = true, output = "3", durationMs = 100)

        executor.executeAfterToolCall(toolContext, toolResult)

        assertEquals(1, capturedResults.size)
        assertEquals("3", capturedResults[0].output)
    }

    @Test
    fun `should execute after agent complete hooks`() = runBlocking {
        val capturedResponses = mutableListOf<AgentResponse>()

        val hook = object : AfterAgentCompleteHook {
            override val order = 1
            override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                capturedResponses.add(response)
            }
        }

        val executor = HookExecutor(
            beforeStartHooks = emptyList(),
            beforeToolCallHooks = emptyList(),
            afterToolCallHooks = emptyList(),
            afterCompleteHooks = listOf(hook)
        )

        val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "Hello")
        val response = AgentResponse(
            success = true,
            response = "Hello! How can I help you?",
            toolsUsed = listOf("greeting_tool")
        )

        executor.executeAfterAgentComplete(context, response)

        assertEquals(1, capturedResponses.size)
        assertEquals("Hello! How can I help you?", capturedResponses[0].response)
    }

    @Test
    fun `should handle pending approval result`() = runBlocking {
        val hook = object : BeforeAgentStartHook {
            override val order = 1
            override suspend fun beforeAgentStart(context: HookContext): HookResult {
                return HookResult.PendingApproval(
                    approvalId = "approval-123",
                    message = "Requires manager approval"
                )
            }
        }

        val executor = HookExecutor(
            beforeStartHooks = listOf(hook),
            beforeToolCallHooks = emptyList(),
            afterToolCallHooks = emptyList(),
            afterCompleteHooks = emptyList()
        )

        val result = executor.executeBeforeAgentStart(
            HookContext(runId = "run-1", userId = "user-1", userPrompt = "Delete all data")
        )

        assertTrue(result is HookResult.PendingApproval)
        assertEquals("Requires manager approval", (result as HookResult.PendingApproval).message)
    }

    @Test
    fun `should fail-close when hook has failOnError true`() = runBlocking {
        val hook = object : BeforeAgentStartHook {
            override val order = 1
            override val failOnError = true
            override suspend fun beforeAgentStart(context: HookContext): HookResult {
                throw RuntimeException("Critical hook failed")
            }
        }

        val executor = HookExecutor(
            beforeStartHooks = listOf(hook),
            beforeToolCallHooks = emptyList(),
            afterToolCallHooks = emptyList(),
            afterCompleteHooks = emptyList()
        )

        val result = executor.executeBeforeAgentStart(
            HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
        )

        assertTrue(result is HookResult.Reject)
        assertTrue((result as HookResult.Reject).reason.contains("Critical hook failed"))
    }

    @Test
    fun `should fail-open when hook has failOnError false`() = runBlocking {
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

        val executor = HookExecutor(
            beforeStartHooks = listOf(failingHook, successHook),
            beforeToolCallHooks = emptyList(),
            afterToolCallHooks = emptyList(),
            afterCompleteHooks = emptyList()
        )

        val result = executor.executeBeforeAgentStart(
            HookContext(runId = "run-1", userId = "user-1", userPrompt = "test")
        )

        assertTrue(result is HookResult.Continue)
        assertEquals(listOf(2), executionOrder)
    }

    @Test
    fun `should mask sensitive params in ToolCallContext`() {
        val agentContext = HookContext(runId = "run-1", userId = "user-1", userPrompt = "Test")
        val toolContext = ToolCallContext(
            agentContext = agentContext,
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
