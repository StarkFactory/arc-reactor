package com.arc.reactor.agent

import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
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
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions

/**
 * P0 Critical Fix Tests:
 * 1. ToolCallback.inputSchema passed to LLM
 * 2. LlmProperties (temperature, maxTokens) applied
 * 3. Manual tool calling loop with maxToolCalls enforcement
 * 4. BeforeToolCallHook invocation
 */
class P0CriticalTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
        fixture.mockCallResponse()
    }

    // =========================================================================
    // P0-1: Tool input schema
    // =========================================================================
    @Nested
    inner class ToolInputSchema {

        @Test
        fun `ToolCallback with custom inputSchema should register schema with ChatClient`() = runBlocking {
            val customSchema = """{"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}"""
            val callback = object : ToolCallback {
                override val name = "get_weather"
                override val description = "Get weather"
                override val inputSchema = customSchema
                override suspend fun call(arguments: Map<String, Any?>) = "Sunny"
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(callback)
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Weather in Seoul?")
            )

            result.assertSuccess()
            io.mockk.verify { fixture.requestSpec.tools(*anyVararg<Any>()) }
        }

        @Test
        fun `ToolCallback without overridden inputSchema defaults to empty object schema`() {
            val callback = AgentTestFixture.toolCallback("simple_tool", "Simple")
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
            every { fixture.requestSpec.options(capture(optionsSlot)) } returns fixture.requestSpec

            val props = properties.copy(llm = properties.llm.copy(temperature = 0.7))
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = props)

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            assertTrue(optionsSlot.isCaptured) { "ChatOptions should be captured" }
            assertEquals(0.7, optionsSlot.captured.temperature)
        }

        @Test
        fun `should pass maxOutputTokens from properties to ChatOptions`() = runBlocking {
            val optionsSlot = slot<ChatOptions>()
            every { fixture.requestSpec.options(capture(optionsSlot)) } returns fixture.requestSpec

            val props = properties.copy(llm = properties.llm.copy(maxOutputTokens = 2048))
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = props)

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            assertTrue(optionsSlot.isCaptured) { "ChatOptions should be captured" }
            assertEquals(2048, optionsSlot.captured.maxTokens)
        }

        @Test
        fun `should override properties temperature with command temperature`() = runBlocking {
            val optionsSlot = slot<ChatOptions>()
            every { fixture.requestSpec.options(capture(optionsSlot)) } returns fixture.requestSpec

            val props = properties.copy(llm = properties.llm.copy(temperature = 0.3))
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = props)

            executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello", temperature = 0.9)
            )

            assertTrue(optionsSlot.isCaptured) { "ChatOptions should be captured" }
            assertEquals(0.9, optionsSlot.captured.temperature)
        }

        @Test
        fun `should use ToolCallingChatOptions when tools are present`() = runBlocking {
            val optionsSlot = slot<ChatOptions>()
            every { fixture.requestSpec.options(capture(optionsSlot)) } returns fixture.requestSpec

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(AgentTestFixture.toolCallback("tool1"))
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            assertTrue(optionsSlot.isCaptured) { "ChatOptions should be captured" }
            assertInstanceOf(ToolCallingChatOptions::class.java, optionsSlot.captured,
                "Should use ToolCallingChatOptions when tools are present")
        }
    }

    // =========================================================================
    // P0-3: Manual tool calling loop + maxToolCalls
    // =========================================================================
    @Nested
    inner class ManualToolCallingLoop {

        @Test
        fun `should execute tool and return final answer`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", """{"q":"test"}""")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockFinalResponse("Final answer after tool use")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(AgentTestFixture.toolCallback("my_tool", result = "tool result"))
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use the tool")
            )

            result.assertSuccess()
            assertEquals("Final answer after tool use", result.content)
            assertTrue(result.toolsUsed.contains("my_tool"), "toolsUsed should contain 'my_tool'")
        }

        @Test
        fun `should enforce maxToolCalls limit`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockFinalResponse("Done")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(AgentTestFixture.toolCallback("my_tool"))
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Loop", maxToolCalls = 2)
            )

            result.assertSuccess()
            assertTrue(result.toolsUsed.size <= 2,
                "toolsUsed (${result.toolsUsed.size}) should not exceed maxToolCalls (2)")
        }
    }

    // =========================================================================
    // P0-4: BeforeToolCallHook invocation
    // =========================================================================
    @Nested
    inner class BeforeToolCallHookInvocation {

        @Test
        fun `should call BeforeToolCallHook before each tool execution`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockFinalResponse("Done")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(AgentTestFixture.toolCallback("my_tool")),
                hookExecutor = hookExecutor
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Use tool"))

            coVerify { hookExecutor.executeBeforeToolCall(match { it.toolName == "my_tool" }) }
        }

        @Test
        fun `should reject tool call when BeforeToolCallHook rejects`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Reject("Dangerous tool")

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockFinalResponse("Understood, tool was blocked")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(AgentTestFixture.toolCallback("my_tool")),
                hookExecutor = hookExecutor
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            )

            result.assertSuccess()
            assertFalse(result.toolsUsed.contains("my_tool"),
                "Tool rejected by hook should NOT be in toolsUsed")
        }
    }
}
