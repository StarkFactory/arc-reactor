package com.arc.reactor.agent

import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
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
 * ReAct 루프 (execute() 경로)에 대한 P0 엣지 케이스 테스트.
 *
 * 아직 테스트되지 않은 시나리오를 다룹니다:
 * - LLM이 환각한 도구 이름 (존재하지 않는 도구)
 * - 도구 실행 시 예외 발생
 * - 부분 도구 실패 (성공과 실패의 혼합)
 * - 빈/null LLM 응답
 * - 등록된 도구에 대해 LLM이 도구 호출을 반환하지만 실패하는 경우
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
        fun `환각된 도구 이름을 처리하고 최종 답변까지 계속해야 한다`() = runBlocking {
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
        fun `도구가 환각되었을 때 LLM에 오류 메시지를 전달해야 한다`() = runBlocking {
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

            // ReAct 루프가 계속되었어야 합니다 (2번의 LLM 호출)
            result.assertSuccess()
            assertEquals("Sorry, I used a wrong tool name. The answer is 2.", result.content)
        }
    }

    @Nested
    inner class ToolExceptionInExecute {

        @Test
        fun `도구 예외를 처리하고 최종 답변까지 계속해야 한다`() = runBlocking {
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
        fun `실패한 도구 호출을 메트릭에 기록해야 한다`() = runBlocking {
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
        fun `병렬 도구 호출에서 성공과 실패를 혼합 처리해야 한다`() = runBlocking {
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
        fun `단일 호출에서 실제 도구와 환각된 도구를 혼합 처리해야 한다`() = runBlocking {
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
        fun `LLM이 빈 결과를 반환할 때 OUTPUT_TOO_SHORT 에러를 반환해야 한다`() = runBlocking {
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

            result.assertErrorCode(AgentErrorCode.OUTPUT_TOO_SHORT)
            assertTrue(
                result.content?.isNotBlank() == true,
                "빈 응답 에러에는 사용자 안내 메시지가 포함되어야 한다"
            )
        }

        @Test
        fun `생성 출력 텍스트가 비어있을 때 OUTPUT_TOO_SHORT 에러를 반환해야 한다`() = runBlocking {
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

            result.assertErrorCode(AgentErrorCode.OUTPUT_TOO_SHORT)
            assertTrue(
                result.content?.isNotBlank() == true,
                "빈 응답 에러에는 사용자 안내 메시지가 포함되어야 한다"
            )
        }
    }

    @Nested
    inner class StandardModeToolIsolation {

        @Test
        fun `STANDARD 모드에서는 등록되어도 도구를 호출하지 않아야 한다`() = runBlocking {
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
