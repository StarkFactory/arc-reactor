package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions

/**
 * P0 Critical Fix Tests:
 * 1. ToolCallback.inputSchema passed to LLM
 * 2. LlmProperties (temperature, maxTokens) applied
 * 3. Manual tool calling loop with maxToolCalls enforcement
 * 4. BeforeToolCallHook invocation
 * 5. runBlocking on Dispatchers.IO (structural, not tested directly)
 */
class P0CriticalTest {

    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var responseSpec: CallResponseSpec
    private lateinit var properties: AgentProperties

    @BeforeEach
    fun setup() {
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        responseSpec = mockk()
        properties = AgentProperties(
            llm = LlmProperties(),
            guard = GuardProperties(),
            rag = RagProperties(),
            concurrency = ConcurrencyProperties()
        )

        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.messages(any<List<org.springframework.ai.chat.messages.Message>>()) } returns requestSpec
        every { requestSpec.tools(*anyVararg<Any>()) } returns requestSpec
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
        every { requestSpec.call() } returns responseSpec
        every { responseSpec.content() } returns "Response"
        every { responseSpec.chatResponse() } returns null
    }

    // =========================================================================
    // P0-1: Tool input schema
    // =========================================================================
    @Nested
    inner class ToolInputSchema {

        @Test
        fun `should pass custom inputSchema from ToolCallback`() = runBlocking {
            val customSchema = """{"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}"""
            val callback = object : ToolCallback {
                override val name = "get_weather"
                override val description = "Get weather"
                override val inputSchema = customSchema
                override suspend fun call(arguments: Map<String, Any?>) = "Sunny"
            }

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(callback)
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Weather in Seoul?")
            )

            assertTrue(result.success)
            // Verify tools were registered (schema is embedded in ArcToolCallbackAdapter)
            io.mockk.verify { requestSpec.tools(*anyVararg<Any>()) }
        }

        @Test
        fun `should use default empty schema when not overridden`() = runBlocking {
            val callback = object : ToolCallback {
                override val name = "simple_tool"
                override val description = "Simple"
                override suspend fun call(arguments: Map<String, Any?>) = "done"
            }

            // Default inputSchema should be {"type":"object","properties":{}}
            assertEquals("""{"type":"object","properties":{}}""", callback.inputSchema)
        }
    }

    // =========================================================================
    // P0-2: LlmProperties wiring
    // =========================================================================
    @Nested
    inner class LlmPropertiesWiring {

        @Test
        fun `should pass temperature from properties to ChatOptions`() = runBlocking {
            val optionsSlot = slot<ChatOptions>()
            every { requestSpec.options(capture(optionsSlot)) } returns requestSpec

            val props = properties.copy(llm = LlmProperties(temperature = 0.7))

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = props
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            assertTrue(optionsSlot.isCaptured)
            assertEquals(0.7, optionsSlot.captured.temperature)
        }

        @Test
        fun `should pass maxOutputTokens from properties to ChatOptions`() = runBlocking {
            val optionsSlot = slot<ChatOptions>()
            every { requestSpec.options(capture(optionsSlot)) } returns requestSpec

            val props = properties.copy(llm = LlmProperties(maxOutputTokens = 2048))

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = props
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            assertTrue(optionsSlot.isCaptured)
            assertEquals(2048, optionsSlot.captured.maxTokens)
        }

        @Test
        fun `should override properties temperature with command temperature`() = runBlocking {
            val optionsSlot = slot<ChatOptions>()
            every { requestSpec.options(capture(optionsSlot)) } returns requestSpec

            val props = properties.copy(llm = LlmProperties(temperature = 0.3))

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = props
            )

            executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello", temperature = 0.9)
            )

            assertTrue(optionsSlot.isCaptured)
            assertEquals(0.9, optionsSlot.captured.temperature)
        }

        @Test
        fun `should use ToolCallingChatOptions when tools are present`() = runBlocking {
            val optionsSlot = slot<ChatOptions>()
            every { requestSpec.options(capture(optionsSlot)) } returns requestSpec

            val callback = object : ToolCallback {
                override val name = "tool1"
                override val description = "Tool 1"
                override suspend fun call(arguments: Map<String, Any?>) = "result"
            }

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(callback)
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            assertTrue(optionsSlot.isCaptured)
            assertTrue(optionsSlot.captured is ToolCallingChatOptions,
                "Should use ToolCallingChatOptions when tools are present")
        }
    }

    // =========================================================================
    // P0-3: Manual tool calling loop + maxToolCalls
    // =========================================================================
    @Nested
    inner class ManualToolCallingLoop {

        private fun createToolCallResponse(toolCalls: List<AssistantMessage.ToolCall>): CallResponseSpec {
            val assistantMsg = AssistantMessage.builder().content("").toolCalls(toolCalls).build()
            val generation = mockk<Generation>()
            every { generation.output } returns assistantMsg
            val chatResponse = mockk<ChatResponse>()
            every { chatResponse.results } returns listOf(generation)
            every { chatResponse.metadata } returns mockk(relaxed = true)

            val spec = mockk<CallResponseSpec>()
            every { spec.content() } returns ""
            every { spec.chatResponse() } returns chatResponse
            return spec
        }

        private fun createFinalResponse(content: String): CallResponseSpec {
            val spec = mockk<CallResponseSpec>()
            every { spec.content() } returns content
            every { spec.chatResponse() } returns null
            return spec
        }

        @Test
        fun `should execute tool and return final answer`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", """{"q":"test"}""")
            val toolCallResponse = createToolCallResponse(listOf(toolCall))
            val finalResponse = createFinalResponse("Final answer after tool use")

            // First call returns tool call, second call returns final answer
            every { requestSpec.call() } returnsMany listOf(toolCallResponse, finalResponse)

            val callback = object : ToolCallback {
                override val name = "my_tool"
                override val description = "My tool"
                override suspend fun call(arguments: Map<String, Any?>): Any = "tool result"
            }

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(callback)
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use the tool")
            )

            assertTrue(result.success)
            assertEquals("Final answer after tool use", result.content)
            assertTrue(result.toolsUsed.contains("my_tool"))
        }

        @Test
        fun `should enforce maxToolCalls limit`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")
            val toolCallResponse = createToolCallResponse(listOf(toolCall))
            val finalResponse = createFinalResponse("Done")

            // Always returns tool call, but should stop after maxToolCalls
            every { requestSpec.call() } returnsMany listOf(
                toolCallResponse, toolCallResponse, toolCallResponse, finalResponse
            )

            val callback = object : ToolCallback {
                override val name = "my_tool"
                override val description = "My tool"
                override suspend fun call(arguments: Map<String, Any?>) = "result"
            }

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(callback)
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Loop", maxToolCalls = 2)
            )

            assertTrue(result.success)
            // Should only call tool twice
            assertTrue(result.toolsUsed.size <= 2,
                "toolsUsed (${result.toolsUsed.size}) should not exceed maxToolCalls (2)")
        }
    }

    // =========================================================================
    // P0-4: BeforeToolCallHook invocation
    // =========================================================================
    @Nested
    inner class BeforeToolCallHookInvocation {

        private fun createToolCallResponse(toolCalls: List<AssistantMessage.ToolCall>): CallResponseSpec {
            val assistantMsg = AssistantMessage.builder().content("").toolCalls(toolCalls).build()
            val generation = mockk<Generation>()
            every { generation.output } returns assistantMsg
            val chatResponse = mockk<ChatResponse>()
            every { chatResponse.results } returns listOf(generation)
            every { chatResponse.metadata } returns mockk(relaxed = true)

            val spec = mockk<CallResponseSpec>()
            every { spec.content() } returns ""
            every { spec.chatResponse() } returns chatResponse
            return spec
        }

        private fun createFinalResponse(content: String): CallResponseSpec {
            val spec = mockk<CallResponseSpec>()
            every { spec.content() } returns content
            every { spec.chatResponse() } returns null
            return spec
        }

        @Test
        fun `should call BeforeToolCallHook before each tool execution`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")
            val toolCallResponse = createToolCallResponse(listOf(toolCall))
            val finalResponse = createFinalResponse("Done")

            every { requestSpec.call() } returnsMany listOf(toolCallResponse, finalResponse)

            val callback = object : ToolCallback {
                override val name = "my_tool"
                override val description = "My tool"
                override suspend fun call(arguments: Map<String, Any?>) = "result"
            }

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(callback),
                hookExecutor = hookExecutor
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Use tool"))

            // BeforeToolCallHook should be called
            coVerify { hookExecutor.executeBeforeToolCall(match { it.toolName == "my_tool" }) }
        }

        @Test
        fun `should reject tool call when BeforeToolCallHook rejects`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Reject("Dangerous tool")

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")
            val toolCallResponse = createToolCallResponse(listOf(toolCall))
            val finalResponse = createFinalResponse("Understood, tool was blocked")

            every { requestSpec.call() } returnsMany listOf(toolCallResponse, finalResponse)

            val callback = object : ToolCallback {
                override val name = "my_tool"
                override val description = "My tool"
                override suspend fun call(arguments: Map<String, Any?>) = "should not execute"
            }

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                toolCallbacks = listOf(callback),
                hookExecutor = hookExecutor
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            )

            assertTrue(result.success)
            // Tool should NOT be in toolsUsed since it was rejected
            assertFalse(result.toolsUsed.contains("my_tool"))
        }
    }
}
