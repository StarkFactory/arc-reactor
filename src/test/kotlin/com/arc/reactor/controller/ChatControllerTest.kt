package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebExchange
import reactor.test.StepVerifier

/**
 * Unit tests for ChatController.
 *
 * Tests request-to-command mapping, response construction,
 * and SSE event type conversion for streaming.
 */
class ChatControllerTest {

    private lateinit var agentExecutor: AgentExecutor
    private lateinit var controller: ChatController
    private lateinit var exchange: ServerWebExchange

    @BeforeEach
    fun setup() {
        agentExecutor = mockk()
        controller = ChatController(agentExecutor)
        exchange = mockk()
        every { exchange.attributes } returns mutableMapOf()
    }

    @Nested
    inner class StandardChat {

        @Test
        fun `should return successful response with content`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success(
                content = "Hello!",
                toolsUsed = listOf("calculator")
            )

            val request = ChatRequest(message = "Hi there")
            val response = controller.chat(request, exchange)

            assertTrue(response.success) { "Response should be successful" }
            assertEquals("Hello!", response.content) { "Content should match agent result" }
            assertEquals(listOf("calculator"), response.toolsUsed) { "Tools used should be forwarded" }
            assertNull(response.errorMessage) { "Error message should be null on success" }
        }

        @Test
        fun `should map request fields to AgentCommand correctly`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val request = ChatRequest(
                message = "test message",
                model = "gpt-4o",
                systemPrompt = "custom prompt",
                userId = "user-123",
                metadata = mapOf("key" to "value"),
                responseFormat = ResponseFormat.JSON,
                responseSchema = """{"type":"object"}"""
            )
            controller.chat(request, exchange)

            val captured = commandSlot.captured
            assertEquals("test message", captured.userPrompt) { "userPrompt should match request message" }
            assertEquals("gpt-4o", captured.model) { "model should be forwarded" }
            assertEquals("custom prompt", captured.systemPrompt) { "custom systemPrompt should be used" }
            assertEquals("user-123", captured.userId) { "userId should be forwarded" }
            assertEquals(mapOf("key" to "value"), captured.metadata) { "metadata should be forwarded" }
            assertEquals(ResponseFormat.JSON, captured.responseFormat) { "responseFormat should be forwarded" }
            assertEquals("""{"type":"object"}""", captured.responseSchema) { "responseSchema should be forwarded" }
        }

        @Test
        fun `should use default system prompt when not provided`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"), exchange)

            assertTrue(commandSlot.captured.systemPrompt.contains("helpful AI assistant")) {
                "Default system prompt should contain 'helpful AI assistant'"
            }
        }

        @Test
        fun `should use default metadata when not provided`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"), exchange)

            assertEquals(emptyMap<String, Any>(), commandSlot.captured.metadata) {
                "Default metadata should be empty map"
            }
        }

        @Test
        fun `should return failure response with error message`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.failure(
                errorMessage = "Rate limit exceeded"
            )

            val response = controller.chat(ChatRequest(message = "hello"), exchange)

            assertFalse(response.success) { "Response should indicate failure" }
            assertNull(response.content) { "Content should be null on failure" }
            assertEquals("Rate limit exceeded", response.errorMessage) { "Error message should be forwarded" }
        }

        @Test
        fun `should forward model in response`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            val response = controller.chat(ChatRequest(message = "hi", model = "gemini-2.0-flash"), exchange)

            assertEquals("gemini-2.0-flash", response.model) { "Model should be forwarded in response" }
        }

        @Test
        fun `should resolve userId from exchange attributes when present`() = runTest {
            val authExchange = mockk<ServerWebExchange>()
            every { authExchange.attributes } returns mutableMapOf<String, Any>("userId" to "jwt-user-1")

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello", userId = "request-user"), authExchange)

            assertEquals("jwt-user-1", commandSlot.captured.userId) {
                "Should prefer JWT userId from exchange over request body userId"
            }
        }

        @Test
        fun `should fallback to request userId when exchange has no auth`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello", userId = "request-user"), exchange)

            assertEquals("request-user", commandSlot.captured.userId) {
                "Should use request userId when no JWT present"
            }
        }

        @Test
        fun `should fallback to anonymous when no userId available`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"), exchange)

            assertEquals("anonymous", commandSlot.captured.userId) {
                "Should default to 'anonymous' when no userId"
            }
        }
    }

    @Nested
    inner class StreamingChat {

        @Test
        fun `should return ServerSentEvents with message event type`() {
            coEvery { agentExecutor.executeStream(any()) } returns flowOf("Hello", " ", "World")

            val flux = controller.chatStream(ChatRequest(message = "Hi"), exchange)

            StepVerifier.create(flux)
                .assertNext { sse ->
                    assertEquals("message", sse.event()) { "Event type should be 'message'" }
                    assertEquals("Hello", sse.data()) { "Data should be 'Hello'" }
                }
                .assertNext { sse ->
                    assertEquals("message", sse.event()) { "Event type should be 'message'" }
                    assertEquals(" ", sse.data()) { "Data should be space" }
                }
                .assertNext { sse ->
                    assertEquals("message", sse.event()) { "Event type should be 'message'" }
                    assertEquals("World", sse.data()) { "Data should be 'World'" }
                }
                .assertNext { sse ->
                    assertEquals("done", sse.event()) { "Last event should be 'done'" }
                }
                .verifyComplete()
        }

        @Test
        fun `should convert tool markers to SSE events`() {
            coEvery { agentExecutor.executeStream(any()) } returns flowOf(
                "Thinking...",
                StreamEventMarker.toolStart("calculator"),
                StreamEventMarker.toolEnd("calculator"),
                "The answer is 8."
            )

            val flux = controller.chatStream(ChatRequest(message = "3+5?"), exchange)

            StepVerifier.create(flux)
                .assertNext { sse ->
                    assertEquals("message", sse.event()) { "First should be text" }
                    assertEquals("Thinking...", sse.data()) { "Text content should match" }
                }
                .assertNext { sse ->
                    assertEquals("tool_start", sse.event()) { "Should be tool_start event" }
                    assertEquals("calculator", sse.data()) { "Tool name should be 'calculator'" }
                }
                .assertNext { sse ->
                    assertEquals("tool_end", sse.event()) { "Should be tool_end event" }
                    assertEquals("calculator", sse.data()) { "Tool name should be 'calculator'" }
                }
                .assertNext { sse ->
                    assertEquals("message", sse.event()) { "Should be message event" }
                    assertEquals("The answer is 8.", sse.data()) { "Text content should match" }
                }
                .assertNext { sse ->
                    assertEquals("done", sse.event()) { "Last event should be 'done'" }
                }
                .verifyComplete()
        }

        @Test
        fun `should always emit done event at the end`() {
            coEvery { agentExecutor.executeStream(any()) } returns flowOf("ok")

            val flux = controller.chatStream(ChatRequest(message = "hello"), exchange)

            StepVerifier.create(flux)
                .assertNext { sse ->
                    assertEquals("message", sse.event()) { "Should emit message" }
                }
                .assertNext { sse ->
                    assertEquals("done", sse.event()) { "Should always end with done" }
                    assertEquals("", sse.data()) { "Done event data should be empty" }
                }
                .verifyComplete()
        }

        @Test
        fun `should map streaming request fields to AgentCommand`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.executeStream(capture(commandSlot)) } returns flowOf("ok")

            controller.chatStream(
                ChatRequest(
                    message = "stream test",
                    model = "gpt-4o",
                    userId = "user-456"
                ),
                exchange
            )

            val captured = commandSlot.captured
            assertEquals("stream test", captured.userPrompt) { "userPrompt should match" }
            assertEquals("gpt-4o", captured.model) { "model should be forwarded" }
            assertEquals("user-456", captured.userId) { "userId should be forwarded" }
        }

        @Test
        fun `should use default values for optional streaming fields`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.executeStream(capture(commandSlot)) } returns flowOf("ok")

            controller.chatStream(ChatRequest(message = "hello"), exchange)

            val captured = commandSlot.captured
            assertTrue(captured.systemPrompt.contains("helpful AI assistant")) {
                "Default system prompt should be applied"
            }
            assertEquals(ResponseFormat.TEXT, captured.responseFormat) {
                "Default response format should be TEXT"
            }
            assertEquals(emptyMap<String, Any>(), captured.metadata) {
                "Default metadata should be empty"
            }
        }
    }

    @Nested
    inner class StreamEventMarkerTest {

        @Test
        fun `toolStart should create proper marker`() {
            val marker = StreamEventMarker.toolStart("calculator")

            assertTrue(StreamEventMarker.isMarker(marker)) { "Should be recognized as marker" }
            val parsed = StreamEventMarker.parse(marker)
            assertNotNull(parsed) { "Should be parseable" }
            assertEquals("tool_start", parsed!!.first) { "Event type should be tool_start" }
            assertEquals("calculator", parsed.second) { "Tool name should be calculator" }
        }

        @Test
        fun `toolEnd should create proper marker`() {
            val marker = StreamEventMarker.toolEnd("web_search")

            assertTrue(StreamEventMarker.isMarker(marker)) { "Should be recognized as marker" }
            val parsed = StreamEventMarker.parse(marker)
            assertNotNull(parsed) { "Should be parseable" }
            assertEquals("tool_end", parsed!!.first) { "Event type should be tool_end" }
            assertEquals("web_search", parsed.second) { "Tool name should be web_search" }
        }

        @Test
        fun `regular text should not be a marker`() {
            assertFalse(StreamEventMarker.isMarker("Hello world")) {
                "Regular text should not be a marker"
            }
            assertNull(StreamEventMarker.parse("Hello world")) {
                "Regular text should not be parseable as marker"
            }
        }

        @Test
        fun `empty string should not be a marker`() {
            assertFalse(StreamEventMarker.isMarker("")) { "Empty string should not be a marker" }
            assertNull(StreamEventMarker.parse("")) { "Empty string should not be parseable" }
        }
    }

    @Nested
    inner class RequestDefaults {

        @Test
        fun `ChatRequest should have sensible defaults`() {
            val request = ChatRequest(message = "test")

            assertNull(request.model) { "model should default to null" }
            assertNull(request.systemPrompt) { "systemPrompt should default to null" }
            assertNull(request.userId) { "userId should default to null" }
            assertNull(request.metadata) { "metadata should default to null" }
            assertNull(request.responseFormat) { "responseFormat should default to null" }
            assertNull(request.responseSchema) { "responseSchema should default to null" }
        }

        @Test
        fun `ChatResponse should include all fields`() {
            val response = ChatResponse(
                content = "result",
                success = true,
                model = "gemini",
                toolsUsed = listOf("calc"),
                errorMessage = null
            )

            assertEquals("result", response.content) { "content should match" }
            assertTrue(response.success) { "success should be true" }
            assertEquals("gemini", response.model) { "model should match" }
            assertEquals(listOf("calc"), response.toolsUsed) { "toolsUsed should match" }
            assertNull(response.errorMessage) { "errorMessage should be null" }
        }
    }
}
