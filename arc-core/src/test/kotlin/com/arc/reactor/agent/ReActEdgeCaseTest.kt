package com.arc.reactor.agent

import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.tool.ToolCallback
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

/**
 * P0 Edge Case Tests for the ReAct loop (execute() path).
 *
 * Covers scenarios not yet tested:
 * - LLM hallucinated tool names (non-existent tools)
 * - Tool execution throwing exceptions
 * - Partial tool failure (mix of success and failure)
 * - Empty/null LLM response
 * - LLM returning tool call for registered tool that then fails
 */
class ReActEdgeCaseTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class HallucinatedToolInExecute {

        @Test
        fun `should handle hallucinated tool name and continue to final answer`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "nonexistent_tool", "{}")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockFinalResponse("I couldn't find that tool, here is my answer instead")
            )

            val realTool = TrackingTool("real_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(realTool)
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use nonexistent_tool")
            )

            result.assertSuccess()
            assertEquals(0, realTool.callCount, "Real tool should not be called")
            assertFalse(result.toolsUsed.contains("nonexistent_tool"),
                "Hallucinated tool should NOT be in toolsUsed list")
            assertFalse(result.toolsUsed.contains("real_tool"),
                "Real tool should NOT be in toolsUsed since it was never called")
        }

        @Test
        fun `should forward error message to LLM when tool is hallucinated`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "imaginary_calc", """{"expr":"1+1"}""")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockFinalResponse("Sorry, I used a wrong tool name. The answer is 2.")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(AgentTestFixture.toolCallback("calculator"))
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Calculate 1+1")
            )

            // The ReAct loop should have continued (2 LLM calls)
            result.assertSuccess()
            assertEquals("Sorry, I used a wrong tool name. The answer is 2.", result.content)
        }
    }

    @Nested
    inner class ToolExceptionInExecute {

        @Test
        fun `should handle tool exception and continue to final answer`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "failing_tool", "{}")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockFinalResponse("The tool failed, but I can still help")
            )

            val failingTool = object : ToolCallback {
                override val name = "failing_tool"
                override val description = "A tool that throws"
                override suspend fun call(arguments: Map<String, Any?>): Any {
                    throw RuntimeException("Database connection refused")
                }
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(failingTool)
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use the tool")
            )

            result.assertSuccess()
            assertEquals("The tool failed, but I can still help", result.content)
            assertTrue(result.toolsUsed.contains("failing_tool"),
                "Tool that was found and attempted should still be in toolsUsed")
        }

        @Test
        fun `should record failed tool call in metrics`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "bad_tool", "{}")
            val metrics = mockk<AgentMetrics>(relaxed = true)

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockFinalResponse("Done")
            )

            val failingTool = object : ToolCallback {
                override val name = "bad_tool"
                override val description = "Fails"
                override suspend fun call(arguments: Map<String, Any?>): Any {
                    throw IllegalStateException("Out of memory")
                }
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(failingTool),
                agentMetrics = metrics
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Go"))

            verify(exactly = 1) { metrics.recordToolCall("bad_tool", any(), false) }
        }
    }

    @Nested
    inner class PartialToolFailure {

        @Test
        fun `should handle mixed success and failure in parallel tool calls`() = runBlocking {
            val successToolCall = AssistantMessage.ToolCall("call-1", "function", "weather", """{"city":"Seoul"}""")
            val failToolCall = AssistantMessage.ToolCall("call-2", "function", "stock", """{"ticker":"AAPL"}""")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(successToolCall, failToolCall)),
                fixture.mockFinalResponse("Weather is sunny. Stock data unavailable.")
            )

            val weatherTool = TrackingTool("weather", "Sunny 25C")
            val stockTool = object : ToolCallback {
                override val name = "stock"
                override val description = "Stock price"
                override suspend fun call(arguments: Map<String, Any?>): Any {
                    throw RuntimeException("API rate limit exceeded")
                }
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(weatherTool, stockTool)
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Weather and stocks")
            )

            result.assertSuccess()
            assertEquals(1, weatherTool.callCount, "Weather tool should be called once")
            assertTrue(result.toolsUsed.contains("weather"),
                "Successful tool should be in toolsUsed")
            assertTrue(result.toolsUsed.contains("stock"),
                "Failed tool (that was found) should also be in toolsUsed")
        }

        @Test
        fun `should handle mix of real and hallucinated tools in single call`() = runBlocking {
            val realCall = AssistantMessage.ToolCall("call-1", "function", "calculator", """{"expr":"2+2"}""")
            val fakeCall = AssistantMessage.ToolCall("call-2", "function", "quantum_computer", """{"algo":"shor"}""")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(realCall, fakeCall)),
                fixture.mockFinalResponse("2+2=4. Quantum computer is not available.")
            )

            val calculator = TrackingTool("calculator", "4")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(calculator)
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Calculate and quantum")
            )

            result.assertSuccess()
            assertEquals(1, calculator.callCount, "Real tool should be called exactly once")
            assertTrue(result.toolsUsed.contains("calculator"),
                "Real tool should be in toolsUsed")
            assertFalse(result.toolsUsed.contains("quantum_computer"),
                "Hallucinated tool should NOT be in toolsUsed")
        }
    }

    @Nested
    inner class EmptyLlmResponse {

        @Test
        fun `should return success with empty content when LLM returns empty results`() = runBlocking {
            val emptyChatResponse = mockk<ChatResponse>()
            every { emptyChatResponse.results } returns emptyList()
            every { emptyChatResponse.metadata } returns mockk(relaxed = true)
            every { fixture.callResponseSpec.chatResponse() } returns emptyChatResponse

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            result.assertSuccess()
            assertEquals("", result.content,
                "Empty LLM response should return empty string content, not null")
        }

        @Test
        fun `should return success with empty content when generation output text is empty`() = runBlocking {
            val assistantMsg = AssistantMessage("")
            val generation = Generation(assistantMsg)

            val chatResponse = ChatResponse(listOf(generation))
            every { fixture.callResponseSpec.chatResponse() } returns chatResponse

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            result.assertSuccess()
            assertEquals("", result.content,
                "Empty assistant text should yield empty content string")
        }
    }

    @Nested
    inner class StandardModeToolIsolation {

        @Test
        fun `STANDARD mode should never call tools even if registered`() = runBlocking {
            fixture.mockCallResponse("Direct response without tools")

            val tool = TrackingTool("my_tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool)
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "Hello",
                    mode = AgentMode.STANDARD
                )
            )

            result.assertSuccess()
            assertEquals(0, tool.callCount, "Tool should not be called in STANDARD mode")
            assertTrue(result.toolsUsed.isEmpty(),
                "No tools should be used in STANDARD mode")
        }
    }
}
