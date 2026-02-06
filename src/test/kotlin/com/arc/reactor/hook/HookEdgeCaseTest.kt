package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class HookEdgeCaseTest {

    private fun createContext() = HookContext(
        runId = "run-1",
        userId = "user-1",
        userPrompt = "test"
    )

    @Test
    fun `should skip disabled hooks`() = runBlocking {
        val executionOrder = mutableListOf<Int>()

        val enabledHook = object : BeforeAgentStartHook {
            override val order = 1
            override val enabled = true
            override suspend fun beforeAgentStart(context: HookContext): HookResult {
                executionOrder.add(1)
                return HookResult.Continue
            }
        }

        val disabledHook = object : BeforeAgentStartHook {
            override val order = 2
            override val enabled = false
            override suspend fun beforeAgentStart(context: HookContext): HookResult {
                executionOrder.add(2)
                return HookResult.Reject("Should not reach here")
            }
        }

        val executor = HookExecutor(
            beforeStartHooks = listOf(enabledHook, disabledHook)
        )

        val result = executor.executeBeforeAgentStart(createContext())

        assertTrue(result is HookResult.Continue)
        assertEquals(listOf(1), executionOrder) // Only enabled hook ran
    }

    @Test
    fun `should return Continue when no hooks registered`() = runBlocking {
        val executor = HookExecutor()

        val result = executor.executeBeforeAgentStart(createContext())
        assertTrue(result is HookResult.Continue)
    }

    @Test
    fun `should return Continue when no tool call hooks registered`() = runBlocking {
        val executor = HookExecutor()

        val toolContext = ToolCallContext(
            agentContext = createContext(),
            toolName = "test",
            toolParams = emptyMap(),
            callIndex = 0
        )

        val result = executor.executeBeforeToolCall(toolContext)
        assertTrue(result is HookResult.Continue)
    }

    @Test
    fun `afterToolCall should propagate exception when failOnError is true`() {
        val failingHook = object : AfterToolCallHook {
            override val order = 1
            override val failOnError = true
            override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                throw RuntimeException("Critical failure")
            }
        }

        val executor = HookExecutor(afterToolCallHooks = listOf(failingHook))

        val toolContext = ToolCallContext(
            agentContext = createContext(),
            toolName = "test",
            toolParams = emptyMap(),
            callIndex = 0
        )

        assertThrows<RuntimeException> {
            runBlocking {
                executor.executeAfterToolCall(toolContext, ToolCallResult(success = true))
            }
        }
    }

    @Test
    fun `afterToolCall should continue when failOnError is false`() = runBlocking {
        val executed = mutableListOf<String>()

        val failingHook = object : AfterToolCallHook {
            override val order = 1
            override val failOnError = false
            override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                executed.add("failing")
                throw RuntimeException("Non-critical failure")
            }
        }

        val successHook = object : AfterToolCallHook {
            override val order = 2
            override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                executed.add("success")
            }
        }

        val executor = HookExecutor(afterToolCallHooks = listOf(failingHook, successHook))

        val toolContext = ToolCallContext(
            agentContext = createContext(),
            toolName = "test",
            toolParams = emptyMap(),
            callIndex = 0
        )

        // Should not throw
        executor.executeAfterToolCall(toolContext, ToolCallResult(success = true))
        assertEquals(listOf("failing", "success"), executed)
    }

    @Test
    fun `afterAgentComplete should propagate exception when failOnError is true`() {
        val failingHook = object : AfterAgentCompleteHook {
            override val order = 1
            override val failOnError = true
            override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                throw RuntimeException("Critical audit failure")
            }
        }

        val executor = HookExecutor(afterCompleteHooks = listOf(failingHook))

        assertThrows<RuntimeException> {
            runBlocking {
                executor.executeAfterAgentComplete(
                    createContext(),
                    AgentResponse(success = true, response = "done")
                )
            }
        }
    }

    @Test
    fun `HookContext toolsUsed should be thread-safe`() {
        val context = createContext()

        // CopyOnWriteArrayList should not throw on concurrent modification
        val threads = (1..10).map { i ->
            Thread {
                repeat(10) { j ->
                    context.toolsUsed.add("tool-$i-$j")
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(100, context.toolsUsed.size)
    }

    @Test
    fun `HookContext metadata should be thread-safe`() {
        val context = createContext()

        val threads = (1..10).map { i ->
            Thread {
                repeat(10) { j ->
                    context.metadata["key-$i-$j"] = "value-$i-$j"
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(100, context.metadata.size)
    }

    @Test
    fun `HookContext durationMs should be non-negative`() {
        val context = createContext()
        assertTrue(context.durationMs() >= 0)
    }

    @Test
    fun `HookResult Modify should carry parameters`() {
        val params = mapOf("key" to "modified-value" as Any)
        val result = HookResult.Modify(params)
        assertEquals("modified-value", result.modifiedParams["key"])
    }
}
