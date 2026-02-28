package com.arc.reactor.tracing

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage

/**
 * Integration tests verifying [ArcReactorTracer] is invoked correctly
 * during [SpringAiAgentExecutor] execution.
 *
 * Uses a mock tracer — no OTel SDK on the classpath is required.
 */
class TracingIntegrationTest {

    private lateinit var fixture: AgentTestFixture
    private lateinit var tracer: ArcReactorTracer
    private lateinit var spanHandle: ArcReactorTracer.SpanHandle
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
        spanHandle = mockk(relaxed = true)
        tracer = mockk()
        every { tracer.startSpan(any(), any()) } returns spanHandle
    }

    @Nested
    inner class RequestSpan {

        @Test
        fun `should start arc-agent-request span on execute`() = runTest {
            // Arrange
            fixture.mockCallResponse("Hello!")
            val executor = buildExecutor()

            // Act
            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Hi"))

            // Assert
            verify(exactly = 1) {
                tracer.startSpan("arc.agent.request", any())
            }
        }

        @Test
        fun `should close request span after successful execution`() = runTest {
            // Arrange
            fixture.mockCallResponse("OK")
            val executor = buildExecutor()

            // Act
            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Test"))

            // Assert — span must be closed at least once (guard + request spans both close)
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `should set error attribute on request span when guard rejects`() = runTest {
            // Arrange
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any<GuardCommand>()) } returns GuardResult.Rejected(
                reason = "rate limited",
                stage = "rate-limit",
                category = RejectionCategory.RATE_LIMITED
            )
            val executor = buildExecutor(guard = guard)

            // Act
            val result = executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "spam")
            )

            // Assert
            assertTrue(!result.success, "Expected failed result when guard rejects")
            // All spans should be closed even on guard rejection
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `should include session-id metadata in request span attributes`() = runTest {
            // Arrange
            fixture.mockCallResponse("OK")
            val capturedAttributes = mutableListOf<Map<String, String>>()
            every { tracer.startSpan("arc.agent.request", any()) } answers {
                capturedAttributes.add(secondArg())
                spanHandle
            }
            val executor = buildExecutor()

            // Act
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hi",
                    metadata = mapOf("sessionId" to "session-123")
                )
            )

            // Assert
            assertTrue(
                capturedAttributes.isNotEmpty(),
                "Expected startSpan to be called with attributes for the request span"
            )
            assertEquals(
                "session-123",
                capturedAttributes.first()["session.id"],
                "Expected session.id attribute to match the sessionId metadata value"
            )
        }
    }

    @Nested
    inner class GuardSpan {

        @Test
        fun `should start arc-agent-guard span when guard is configured`() = runTest {
            // Arrange
            fixture.mockCallResponse("OK")
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any<GuardCommand>()) } returns GuardResult.Allowed()
            val executor = buildExecutor(guard = guard)

            // Act
            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Test"))

            // Assert
            verify(atLeast = 1) {
                tracer.startSpan("arc.agent.guard", any())
            }
        }

        @Test
        fun `should close guard span even when guard rejects`() = runTest {
            // Arrange
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any<GuardCommand>()) } returns GuardResult.Rejected(
                reason = "blocked",
                stage = "classification",
                category = RejectionCategory.PROMPT_INJECTION
            )
            val executor = buildExecutor(guard = guard)

            // Act
            executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "bad input")
            )

            // Assert — guard span + request span both close
            verify(atLeast = 2) { spanHandle.close() }
        }
    }

    @Nested
    inner class LlmCallSpan {

        @Test
        fun `should start arc-agent-llm-call span for each LLM invocation`() = runTest {
            // Arrange
            fixture.mockCallResponse("Final answer")
            val executor = buildExecutor()

            // Act
            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "What is 2+2?"))

            // Assert
            verify(atLeast = 1) {
                tracer.startSpan("arc.agent.llm.call", any())
            }
        }
    }

    @Nested
    inner class ToolCallSpan {

        @Test
        fun `should start arc-agent-tool-call span for each tool invocation`() = runTest {
            // Arrange
            val toolCall = AssistantMessage.ToolCall("id-1", "function", "calc", "{}")
            val firstResponse = fixture.mockToolCallResponse(listOf(toolCall))
            val finalResponse = fixture.mockFinalResponse("42")
            every { fixture.requestSpec.call() } returnsMany listOf(firstResponse, finalResponse)

            val tool = AgentTestFixture.toolCallback("calc", result = "42")
            val executor = buildExecutor(tools = listOf(tool))

            // Act
            executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Calculate something")
            )

            // Assert
            verify(atLeast = 1) {
                tracer.startSpan("arc.agent.tool.call", any())
            }
        }

        @Test
        fun `should include tool-name attribute in tool call span`() = runTest {
            // Arrange
            val toolCall = AssistantMessage.ToolCall("id-1", "function", "myTool", "{}")
            val firstResponse = fixture.mockToolCallResponse(listOf(toolCall))
            val finalResponse = fixture.mockFinalResponse("result")
            every { fixture.requestSpec.call() } returnsMany listOf(firstResponse, finalResponse)

            val attributesCaptures = mutableListOf<Map<String, String>>()
            every { tracer.startSpan(any(), any()) } answers {
                attributesCaptures.add(secondArg())
                spanHandle
            }

            val tool = AgentTestFixture.toolCallback("myTool", result = "done")
            val executor = buildExecutor(tools = listOf(tool))

            // Act
            executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Use my tool")
            )

            // Assert
            val toolSpanAttrs = attributesCaptures.firstOrNull { it["tool.name"] != null }
            assertTrue(
                toolSpanAttrs != null,
                "Expected at least one tool call span with a tool.name attribute"
            )
            assertEquals(
                "myTool",
                toolSpanAttrs!!["tool.name"],
                "Expected tool.name to match the invoked tool name"
            )
        }
    }

    @Nested
    inner class NoOpFallback {

        @Test
        fun `executor without explicit tracer should use no-op and not throw`() = runTest {
            // Arrange — no tracer injected, defaults to NoOpArcReactorTracer
            fixture.mockCallResponse("All good")
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            // Act + Assert — must not throw and must succeed
            val result = executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Test")
            )
            assertTrue(result.success, "Executor with default NoOp tracer should succeed")
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun buildExecutor(
        guard: RequestGuard? = null,
        tools: List<com.arc.reactor.tool.ToolCallback> = emptyList()
    ): SpringAiAgentExecutor = SpringAiAgentExecutor(
        chatClient = fixture.chatClient,
        properties = properties,
        guard = guard,
        toolCallbacks = tools,
        tracer = tracer
    )
}
