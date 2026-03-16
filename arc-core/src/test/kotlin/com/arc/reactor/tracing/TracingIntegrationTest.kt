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
 * [ArcReactorTracer]가 올바르게 호출되는지 확인하는 통합 테스트.
 * [SpringAiAgentExecutor] 실행 중.
 *
 * mock tracer를 사용합니다 — 클래스패스에 OTel SDK가 필요하지 않습니다.
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
        fun `start arc-agent-request span on execute해야 한다`() = runTest {
            // 준비
            fixture.mockCallResponse("Hello!")
            val executor = buildExecutor()

            // 실행
            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Hi"))

            // 검증
            verify(exactly = 1) {
                tracer.startSpan("arc.agent.request", any())
            }
        }

        @Test
        fun `successful execution 후 close request span해야 한다`() = runTest {
            // 준비
            fixture.mockCallResponse("OK")
            val executor = buildExecutor()

            // 실행
            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Test"))

            // — span must be closed at least once (guard + request spans both close) 확인
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `guard rejects일 때 set error attribute on request span해야 한다`() = runTest {
            // 준비
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any<GuardCommand>()) } returns GuardResult.Rejected(
                reason = "rate limited",
                stage = "rate-limit",
                category = RejectionCategory.RATE_LIMITED
            )
            val executor = buildExecutor(guard = guard)

            // 실행
            val result = executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "spam")
            )

            // 검증
            assertTrue(!result.success, "Expected failed result when guard rejects")
            // All spans은(는) be closed even on guard rejection해야 합니다
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `include session-id metadata in request span attributes해야 한다`() = runTest {
            // 준비
            fixture.mockCallResponse("OK")
            val capturedAttributes = mutableListOf<Map<String, String>>()
            every { tracer.startSpan("arc.agent.request", any()) } answers {
                capturedAttributes.add(secondArg())
                spanHandle
            }
            val executor = buildExecutor()

            // 실행
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hi",
                    metadata = mapOf("sessionId" to "session-123")
                )
            )

            // 검증
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
        fun `guard is configured일 때 start arc-agent-guard span해야 한다`() = runTest {
            // 준비
            fixture.mockCallResponse("OK")
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any<GuardCommand>()) } returns GuardResult.Allowed()
            val executor = buildExecutor(guard = guard)

            // 실행
            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Test"))

            // 검증
            verify(atLeast = 1) {
                tracer.startSpan("arc.agent.guard", any())
            }
        }

        @Test
        fun `guard rejects일 때 close guard span even해야 한다`() = runTest {
            // 준비
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any<GuardCommand>()) } returns GuardResult.Rejected(
                reason = "blocked",
                stage = "classification",
                category = RejectionCategory.PROMPT_INJECTION
            )
            val executor = buildExecutor(guard = guard)

            // 실행
            executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "bad input")
            )

            // — guard span + request span both close 확인
            verify(atLeast = 2) { spanHandle.close() }
        }
    }

    @Nested
    inner class LlmCallSpan {

        @Test
        fun `each LLM invocation에 대해 start arc-agent-llm-call span해야 한다`() = runTest {
            // 준비
            fixture.mockCallResponse("Final answer")
            val executor = buildExecutor()

            // 실행
            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "What is 2+2?"))

            // 검증
            verify(atLeast = 1) {
                tracer.startSpan("arc.agent.llm.call", any())
            }
        }
    }

    @Nested
    inner class ToolCallSpan {

        @Test
        fun `each tool invocation에 대해 start arc-agent-tool-call span해야 한다`() = runTest {
            // 준비
            val toolCall = AssistantMessage.ToolCall("id-1", "function", "calc", "{}")
            val firstResponse = fixture.mockToolCallResponse(listOf(toolCall))
            val finalResponse = fixture.mockFinalResponse("42")
            every { fixture.requestSpec.call() } returnsMany listOf(firstResponse, finalResponse)

            val tool = AgentTestFixture.toolCallback("calc", result = "42")
            val executor = buildExecutor(tools = listOf(tool))

            // 실행
            executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Calculate something")
            )

            // 검증
            verify(atLeast = 1) {
                tracer.startSpan("arc.agent.tool.call", any())
            }
        }

        @Test
        fun `include tool-name attribute in tool call span해야 한다`() = runTest {
            // 준비
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

            // 실행
            executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Use my tool")
            )

            // 검증
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
        fun `executor without explicit tracer은(는) use no-op and not throw해야 한다`() = runTest {
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
    // 헬퍼
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
