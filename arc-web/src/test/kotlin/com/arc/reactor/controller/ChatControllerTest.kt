package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.MultimodalProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.persona.PersonaStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import reactor.test.StepVerifier
import java.net.URI

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

    private fun mockExchange(
        attributes: MutableMap<String, Any> = mutableMapOf(),
        headers: HttpHeaders = HttpHeaders()
    ): ServerWebExchange {
        val request = mockk<ServerHttpRequest>()
        every { request.headers } returns headers
        val ex = mockk<ServerWebExchange>()
        every { ex.attributes } returns attributes
        every { ex.request } returns request
        return ex
    }

    @BeforeEach
    fun setup() {
        agentExecutor = mockk()
        controller = ChatController(agentExecutor)
        exchange = mockExchange()
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
            assertEquals(
                mapOf("key" to "value", "channel" to "web", "tenantId" to "default"),
                captured.metadata
            ) { "metadata should be forwarded with tenantId" }
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
        fun `should fallback to hardcoded prompt when default persona lookup fails`() = runTest {
            val failingPersonaStore = mockk<PersonaStore>()
            every { failingPersonaStore.getDefault() } throws RuntimeException("relation personas does not exist")

            val fallbackController = ChatController(
                agentExecutor = agentExecutor,
                personaStore = failingPersonaStore
            )

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            fallbackController.chat(ChatRequest(message = "hello"), exchange)

            assertTrue(commandSlot.captured.systemPrompt.contains("helpful AI assistant")) {
                "Controller should fallback to hardcoded prompt when persona lookup throws"
            }
        }

        @Test
        fun `should use default metadata when not provided`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"), exchange)

            assertEquals(mapOf("channel" to "web", "tenantId" to "default"), commandSlot.captured.metadata) {
                "Default metadata should contain channel=web and tenantId=default"
            }
        }

        @Test
        fun `should prefer resolved tenantId exchange attribute over request header`() = runTest {
            val headers = HttpHeaders()
            headers.add("X-Tenant-Id", "resolved-tenant")
            val tenantExchange = mockExchange(
                attributes = mutableMapOf("resolvedTenantId" to "resolved-tenant"),
                headers = headers
            )

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"), tenantExchange)

            assertEquals("resolved-tenant", commandSlot.captured.metadata["tenantId"]) {
                "Resolved tenant attribute should take precedence over raw header"
            }
        }

        @Test
        fun `should reject request when tenant header mismatches resolved tenant context`() = runTest {
            val headers = HttpHeaders()
            headers.add("X-Tenant-Id", "tenant-b")
            val tenantExchange = mockExchange(
                attributes = mutableMapOf("resolvedTenantId" to "tenant-a"),
                headers = headers
            )

            val ex = try {
                controller.chat(ChatRequest(message = "hello"), tenantExchange)
                throw AssertionError("Mismatched tenant context should throw ServerWebInputException")
            } catch (e: ServerWebInputException) {
                e
            }
            assertTrue(ex.reason?.contains("Tenant header does not match resolved tenant context") == true) {
                "Mismatched tenant context should be rejected"
            }
        }

        @Test
        fun `should reject request when auth enabled and tenant context is missing`() = runTest {
            val strictController = ChatController(
                agentExecutor = agentExecutor,
                authProperties = AuthProperties(enabled = true)
            )

            val ex = try {
                strictController.chat(ChatRequest(message = "hello"), mockExchange())
                throw AssertionError("Missing tenant context should throw ServerWebInputException")
            } catch (e: ServerWebInputException) {
                e
            }
            assertTrue(ex.reason?.contains("Missing tenant context") == true) {
                "Auth-enabled mode should fail-close without tenant context"
            }
        }

        @Test
        fun `should allow request when auth enabled and tenant header is provided`() = runTest {
            val strictController = ChatController(
                agentExecutor = agentExecutor,
                authProperties = AuthProperties(enabled = true)
            )
            val headers = HttpHeaders()
            headers.add("X-Tenant-Id", "tenant-secure")
            val tenantExchange = mockExchange(headers = headers)

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            strictController.chat(ChatRequest(message = "hello"), tenantExchange)

            assertEquals("tenant-secure", commandSlot.captured.metadata["tenantId"]) {
                "Auth-enabled mode should accept explicit tenant context"
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
            val authExchange = mockExchange(mutableMapOf("userId" to "jwt-user-1"))

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
        fun `should return ServerSentEvents with message event type`() = runTest {
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
        fun `should convert tool markers to SSE events`() = runTest {
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
        fun `should always emit done event at the end`() = runTest {
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
            assertEquals(mapOf("channel" to "web", "tenantId" to "default"), captured.metadata) {
                "Default metadata should contain channel=web"
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

    @Nested
    inner class MultimodalToggle {

        @Test
        fun `should resolve mediaUrls when multimodal is enabled (default)`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val request = ChatRequest(
                message = "Describe this image",
                mediaUrls = listOf(MediaUrlRequest("https://example.com/photo.png", "image/png"))
            )
            controller.chat(request, exchange)

            assertEquals(1, commandSlot.captured.media.size) {
                "Should have 1 media attachment when multimodal is enabled"
            }
        }

        @Test
        fun `should ignore mediaUrls when multimodal is disabled`() = runTest {
            val disabledProps = AgentProperties(multimodal = MultimodalProperties(enabled = false))
            val disabledController = ChatController(agentExecutor, properties = disabledProps)

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val request = ChatRequest(
                message = "Describe this image",
                mediaUrls = listOf(MediaUrlRequest("https://example.com/photo.png", "image/png"))
            )
            disabledController.chat(request, exchange)

            assertTrue(commandSlot.captured.media.isEmpty()) {
                "Should have no media attachments when multimodal is disabled"
            }
        }

        @Test
        fun `should ignore mediaUrls in streaming when multimodal is disabled`() = runTest {
            val disabledProps = AgentProperties(multimodal = MultimodalProperties(enabled = false))
            val disabledController = ChatController(agentExecutor, properties = disabledProps)

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.executeStream(capture(commandSlot)) } returns flowOf("ok")

            val request = ChatRequest(
                message = "Describe this image",
                mediaUrls = listOf(MediaUrlRequest("https://example.com/photo.png", "image/png"))
            )
            disabledController.chatStream(request, exchange)

            assertTrue(commandSlot.captured.media.isEmpty()) {
                "Streaming should have no media when multimodal is disabled"
            }
        }

        @Test
        fun `should pass empty media when no mediaUrls provided regardless of toggle`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"), exchange)

            assertTrue(commandSlot.captured.media.isEmpty()) {
                "Should have no media when mediaUrls not provided"
            }
        }

        @Test
        fun `should reject invalid media mime type with bad request exception`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            val exception = try {
                controller.chat(
                    ChatRequest(
                        message = "Describe this image",
                        mediaUrls = listOf(MediaUrlRequest("https://example.com/photo.png", "not-a-mime"))
                    ),
                    exchange
                )
                fail("Expected ServerWebInputException for invalid media mime type")
            } catch (e: ServerWebInputException) {
                e
            }
            assertTrue(exception.reason?.contains("Invalid media mimeType") == true) {
                "Invalid mimeType should produce a clear bad request reason"
            }
        }

        @Test
        fun `should reject invalid media url with bad request exception`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            val exception = try {
                controller.chat(
                    ChatRequest(
                        message = "Describe this image",
                        mediaUrls = listOf(MediaUrlRequest("://bad-url", "image/png"))
                    ),
                    exchange
                )
                fail("Expected ServerWebInputException for invalid media URL")
            } catch (e: ServerWebInputException) {
                e
            }
            assertTrue(exception.reason?.contains("Invalid media URL") == true) {
                "Invalid media URL should produce a clear bad request reason"
            }
        }

        @Test
        fun `should reject relative media url with bad request exception`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            val exception = try {
                controller.chat(
                    ChatRequest(
                        message = "Describe this image",
                        mediaUrls = listOf(MediaUrlRequest("images/photo.png", "image/png"))
                    ),
                    exchange
                )
                fail("Expected ServerWebInputException for relative media URL")
            } catch (e: ServerWebInputException) {
                e
            }
            assertTrue(exception.reason?.contains("Invalid media URL") == true) {
                "Relative media URL should be rejected as invalid input"
            }
        }

        @Test
        fun `should reject non-http media url scheme`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            val exception = try {
                controller.chat(
                    ChatRequest(
                        message = "Describe this image",
                        mediaUrls = listOf(MediaUrlRequest("file:///tmp/secret.png", "image/png"))
                    ),
                    exchange
                )
                fail("Expected ServerWebInputException for unsupported media URL scheme")
            } catch (e: ServerWebInputException) {
                e
            }
            assertTrue(exception.reason?.contains("Invalid media URL") == true) {
                "Only http/https media URLs should be accepted"
            }
        }

        @Test
        fun `should accept trimmed https media url and mime type`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(
                ChatRequest(
                    message = "Describe this image",
                    mediaUrls = listOf(MediaUrlRequest("  https://example.com/photo.png  ", " image/png "))
                ),
                exchange
            )

            val media = commandSlot.captured.media
            assertEquals(1, media.size) { "Expected one media attachment for valid trimmed input" }
            assertEquals(URI("https://example.com/photo.png"), media.first().uri) {
                "URL should be trimmed and parsed as an absolute https URI"
            }
        }

        @Test
        fun `should reject absolute https url without host`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            val exception = try {
                controller.chat(
                    ChatRequest(
                        message = "Describe this image",
                        mediaUrls = listOf(MediaUrlRequest("https:///photo.png", "image/png"))
                    ),
                    exchange
                )
                fail("Expected ServerWebInputException for https URL without host")
            } catch (e: ServerWebInputException) {
                e
            }
            assertTrue(exception.reason?.contains("Invalid media URL") == true) {
                "Absolute URLs without host should be rejected"
            }
        }
    }
}
