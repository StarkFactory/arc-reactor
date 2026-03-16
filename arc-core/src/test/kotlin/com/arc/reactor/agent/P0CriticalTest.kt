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
 * P0 핵심 수정 테스트:
 * 1. ToolCallback.inputSchema가 LLM에 전달됨
 * 2. LlmProperties (temperature, maxTokens) 적용됨
 * 3. maxToolCalls 적용이 포함된 수동 도구 호출 루프
 * 4. BeforeToolCallHook 호출
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
    // P0-1: 도구 입력 스키마
    // =========================================================================
    @Nested
    inner class ToolInputSchema {

        @Test
        fun `커스텀 inputSchema를 가진 ToolCallback이 ChatClient에 스키마를 등록해야 한다`() = runBlocking {
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
            io.mockk.verify { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }

        @Test
        fun `오버라이드되지 않은 inputSchema를 가진 ToolCallback이 빈 객체 스키마를 기본값으로 가져야 한다`() {
            val callback = AgentTestFixture.toolCallback("simple_tool", "Simple")
            assertEquals("""{"type":"object","properties":{}}""", callback.inputSchema)
        }
    }

    // =========================================================================
    // P0-2: LlmProperties 연결
    // =========================================================================
    @Nested
    inner class LlmPropertiesWiring {

        @Test
        fun `properties의 temperature를 ChatOptions에 전달해야 한다`() = runBlocking {
            val optionsSlot = slot<ChatOptions>()
            every { fixture.requestSpec.options(capture(optionsSlot)) } returns fixture.requestSpec

            val props = properties.copy(llm = properties.llm.copy(temperature = 0.7))
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = props)

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            assertTrue(optionsSlot.isCaptured) { "ChatOptions should be captured" }
            assertEquals(0.7, optionsSlot.captured.temperature)
        }

        @Test
        fun `properties의 maxOutputTokens를 ChatOptions에 전달해야 한다`() = runBlocking {
            val optionsSlot = slot<ChatOptions>()
            every { fixture.requestSpec.options(capture(optionsSlot)) } returns fixture.requestSpec

            val props = properties.copy(llm = properties.llm.copy(maxOutputTokens = 2048))
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = props)

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            assertTrue(optionsSlot.isCaptured) { "ChatOptions should be captured" }
            assertEquals(2048, optionsSlot.captured.maxTokens)
        }

        @Test
        fun `커맨드 temperature로 properties temperature를 오버라이드해야 한다`() = runBlocking {
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
        fun `도구가 있을 때 ToolCallingChatOptions를 사용해야 한다`() = runBlocking {
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
    // P0-3: 수동 도구 호출 루프 + maxToolCalls
    // =========================================================================
    @Nested
    inner class ManualToolCallingLoop {

        @Test
        fun `도구를 실행하고 최종 응답을 반환해야 한다`() = runBlocking {
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
        fun `maxToolCalls 제한을 적용해야 한다`() = runBlocking {
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
    // P0-4: BeforeToolCallHook 호출
    // =========================================================================
    @Nested
    inner class BeforeToolCallHookInvocation {

        @Test
        fun `각 도구 실행 전에 BeforeToolCallHook을 호출해야 한다`() = runBlocking {
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
        fun `BeforeToolCallHook이 거부하면 도구 호출을 거부해야 한다`() = runBlocking {
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
