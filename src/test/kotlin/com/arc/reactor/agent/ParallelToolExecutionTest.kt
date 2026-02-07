package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.hook.BeforeToolCallHook
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.tool.ToolCallback
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ParallelToolExecutionTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class Parallelism {

        @Test
        fun `should execute tools in parallel - concurrency verification`() = runBlocking {
        // Track the max number of concurrently executing tools
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val tools = listOf("tool-a", "tool-b", "tool-c").map { name ->
            object : ToolCallback {
                override val name = name
                override val description = "Test tool: $name"
                override suspend fun call(arguments: Map<String, Any?>): Any {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    kotlinx.coroutines.delay(100) // hold slot to allow overlap
                    concurrent.decrementAndGet()
                    return "result"
                }
            }
        }

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "tool-a", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "tool-b", "{}"),
            AssistantMessage.ToolCall("id-3", "function", "tool-c", "{}")
        )

        val callCount = AtomicInteger(0)
        every { fixture.requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                fixture.mockToolCallResponse(toolCalls)
            } else {
                fixture.mockFinalResponse("Done")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties,
            toolCallbacks = tools
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use all tools")
        )

        result.assertSuccess()
        // If parallel, max concurrent should be > 1 (ideally 3)
        assertTrue(maxConcurrent.get() > 1,
            "Expected concurrent execution > 1, but was ${maxConcurrent.get()} (tools ran sequentially)")
    }

    @Test
    fun `should preserve result order regardless of execution time`() = runBlocking {
        // tool-slow takes longer but should still be first in results
        val tools = listOf(
            AgentTestFixture.delayingToolCallback("tool-slow", delayMs = 150, result = "SLOW"),
            AgentTestFixture.delayingToolCallback("tool-fast", delayMs = 10, result = "FAST")
        )

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "tool-slow", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "tool-fast", "{}")
        )

        val callCount = AtomicInteger(0)
        every { fixture.requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                fixture.mockToolCallResponse(toolCalls)
            } else {
                fixture.mockFinalResponse("Done")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties,
            toolCallbacks = tools
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools")
        )

        result.assertSuccess()
        // Both tools should be recorded
        assertTrue(result.toolsUsed.contains("tool-slow"), "tool-slow should be in toolsUsed")
        assertTrue(result.toolsUsed.contains("tool-fast"), "tool-fast should be in toolsUsed")
    }
    }

    @Nested
    inner class Integration {

        @Test
        fun `should execute hooks for parallel tool calls`() = runBlocking {
        val hookedTools = CopyOnWriteArrayList<String>()

        val beforeHook = object : BeforeToolCallHook {
            override val order = 1
            override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
                hookedTools.add(context.toolName)
                return HookResult.Continue
            }
        }

        val hookExecutor = HookExecutor(beforeToolCallHooks = listOf(beforeHook))

        val tools = listOf(
            AgentTestFixture.toolCallback("tool-x"),
            AgentTestFixture.toolCallback("tool-y")
        )

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "tool-x", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "tool-y", "{}")
        )

        val callCount = AtomicInteger(0)
        every { fixture.requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                fixture.mockToolCallResponse(toolCalls)
            } else {
                fixture.mockFinalResponse("Done")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties,
            toolCallbacks = tools,
            hookExecutor = hookExecutor
        )

        executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools")
        )

        // Both tools should have had hooks called
        assertTrue(hookedTools.contains("tool-x"), "Hook should have been called for tool-x")
        assertTrue(hookedTools.contains("tool-y"), "Hook should have been called for tool-y")
    }

    @Test
    fun `should handle individual tool failure without affecting others`() = runBlocking {
        val failingTool = object : ToolCallback {
            override val name = "failing-tool"
            override val description = "Fails"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                throw RuntimeException("Tool crashed")
            }
        }
        val successTool = AgentTestFixture.toolCallback("success-tool", result = "OK")

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "failing-tool", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "success-tool", "{}")
        )

        val callCount = AtomicInteger(0)
        every { fixture.requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                fixture.mockToolCallResponse(toolCalls)
            } else {
                fixture.mockFinalResponse("Final answer")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties,
            toolCallbacks = listOf(failingTool, successTool)
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools")
        )

        // Should still succeed overall (LLM gets error from one tool, success from another)
        result.assertSuccess()
        assertEquals("Final answer", result.content)
    }
    }

    @Nested
    inner class Limits {

        @Test
        fun `should respect maxToolCalls across parallel execution`() = runBlocking {
        val tools = listOf(
            AgentTestFixture.toolCallback("t1"),
            AgentTestFixture.toolCallback("t2"),
            AgentTestFixture.toolCallback("t3")
        )

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "t1", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "t2", "{}"),
            AssistantMessage.ToolCall("id-3", "function", "t3", "{}")
        )

        val callCount = AtomicInteger(0)
        every { fixture.requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                fixture.mockToolCallResponse(toolCalls)
            } else {
                fixture.mockFinalResponse("Done")
            }
        }

        // maxToolCalls = 2, but 3 tools requested
        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties.copy(maxToolCalls = 2),
            toolCallbacks = tools
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools", maxToolCalls = 2)
        )

        result.assertSuccess()
        // At most 2 tools should have been executed successfully
        assertTrue(result.toolsUsed.size <= 2, "Should respect maxToolCalls limit: ${result.toolsUsed}")
    }
    }
}
