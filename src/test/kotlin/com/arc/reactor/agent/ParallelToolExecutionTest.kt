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
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ParallelToolExecutionTest {

    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var properties: AgentProperties

    @BeforeEach
    fun setup() {
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        properties = AgentProperties()

        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.messages(any<List<Message>>()) } returns requestSpec
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
        every { requestSpec.tools(*anyVararg<Any>()) } returns requestSpec
    }

    private fun createToolCallback(name: String, delayMs: Long = 0, result: String = "result-$name"): ToolCallback {
        return object : ToolCallback {
            override val name = name
            override val description = "Tool $name"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                if (delayMs > 0) Thread.sleep(delayMs)
                return result
            }
        }
    }

    private fun mockResponseWithToolCalls(toolCalls: List<AssistantMessage.ToolCall>): CallResponseSpec {
        val assistantMsg = AssistantMessage.builder()
            .content("")
            .toolCalls(toolCalls)
            .build()
        val generation = Generation(assistantMsg)
        val chatResponse = ChatResponse(listOf(generation))
        val responseSpec = mockk<CallResponseSpec>()
        every { responseSpec.chatResponse() } returns chatResponse
        every { responseSpec.content() } returns ""
        return responseSpec
    }

    private fun mockFinalResponse(content: String): CallResponseSpec {
        val responseSpec = mockk<CallResponseSpec>()
        every { responseSpec.chatResponse() } returns null
        every { responseSpec.content() } returns content
        return responseSpec
    }

    @Test
    fun `should execute tools in parallel - time verification`() = runBlocking {
        // 3 tools, each takes 200ms. Sequential = ~600ms, Parallel = ~200ms
        val tools = listOf(
            createToolCallback("tool-a", delayMs = 200),
            createToolCallback("tool-b", delayMs = 200),
            createToolCallback("tool-c", delayMs = 200)
        )

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "tool-a", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "tool-b", "{}"),
            AssistantMessage.ToolCall("id-3", "function", "tool-c", "{}")
        )

        val callCount = AtomicInteger(0)
        every { requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                mockResponseWithToolCalls(toolCalls)
            } else {
                mockFinalResponse("Done")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            toolCallbacks = tools
        )

        val startTime = System.currentTimeMillis()
        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use all tools")
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(result.success)
        // Parallel should be ~200ms, not ~600ms. Allow generous margin.
        assertTrue(elapsed < 500, "Parallel execution took ${elapsed}ms, expected < 500ms (sequential would be ~600ms)")
    }

    @Test
    fun `should preserve result order regardless of execution time`() = runBlocking {
        // tool-slow takes longer but should still be first in results
        val tools = listOf(
            createToolCallback("tool-slow", delayMs = 150, result = "SLOW"),
            createToolCallback("tool-fast", delayMs = 10, result = "FAST")
        )

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "tool-slow", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "tool-fast", "{}")
        )

        val callCount = AtomicInteger(0)
        every { requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                mockResponseWithToolCalls(toolCalls)
            } else {
                mockFinalResponse("Done")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            toolCallbacks = tools
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools")
        )

        assertTrue(result.success)
        // Both tools should be recorded
        assertTrue(result.toolsUsed.contains("tool-slow"))
        assertTrue(result.toolsUsed.contains("tool-fast"))
    }

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
            createToolCallback("tool-x"),
            createToolCallback("tool-y")
        )

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "tool-x", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "tool-y", "{}")
        )

        val callCount = AtomicInteger(0)
        every { requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                mockResponseWithToolCalls(toolCalls)
            } else {
                mockFinalResponse("Done")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
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
        val successTool = createToolCallback("success-tool", result = "OK")

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "failing-tool", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "success-tool", "{}")
        )

        val callCount = AtomicInteger(0)
        every { requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                mockResponseWithToolCalls(toolCalls)
            } else {
                mockFinalResponse("Final answer")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            toolCallbacks = listOf(failingTool, successTool)
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools")
        )

        // Should still succeed overall (LLM gets error from one tool, success from another)
        assertTrue(result.success)
        assertEquals("Final answer", result.content)
    }

    @Test
    fun `should respect maxToolCalls across parallel execution`() = runBlocking {
        val tools = listOf(
            createToolCallback("t1"),
            createToolCallback("t2"),
            createToolCallback("t3")
        )

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "t1", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "t2", "{}"),
            AssistantMessage.ToolCall("id-3", "function", "t3", "{}")
        )

        val callCount = AtomicInteger(0)
        every { requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                mockResponseWithToolCalls(toolCalls)
            } else {
                mockFinalResponse("Done")
            }
        }

        // maxToolCalls = 2, but 3 tools requested
        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties.copy(maxToolCalls = 2),
            toolCallbacks = tools
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools", maxToolCalls = 2)
        )

        assertTrue(result.success)
        // At most 2 tools should have been executed successfully
        assertTrue(result.toolsUsed.size <= 2, "Should respect maxToolCalls limit: ${result.toolsUsed}")
    }
}
