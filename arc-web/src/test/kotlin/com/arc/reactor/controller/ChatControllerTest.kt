package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.MultimodalProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.persona.Persona
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.prompt.PromptVersion
import com.arc.reactor.prompt.VersionStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.springframework.http.HttpStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import reactor.test.StepVerifier
import java.net.URI

/**
 * 에 대한 단위 테스트. ChatController.
 *
 * Tests request-to-command mapping, response construction,
 * and SSE event type conversion for streaming.
 */
class ChatControllerTest {

    private lateinit var agentExecutor: AgentExecutor
    private lateinit var controller: ChatController
    private lateinit var exchange: ServerWebExchange

    private fun mockExchange(
        attributes: MutableMap<String, Any> = mutableMapOf("resolvedTenantId" to "default"),
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
        fun `content로 return successful response해야 한다`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success(
                content = "Hello!",
                toolsUsed = listOf("calculator")
            ).copy(
                metadata = mapOf("grounded" to true, "verifiedSourceCount" to 1)
            )

            val request = ChatRequest(message = "Hi there")
            val entity = controller.chat(request, exchange)

            assertEquals(HttpStatus.OK, entity.statusCode) { "Successful result should return HTTP 200" }
            val response = entity.body!!
            assertTrue(response.success) { "Response should be successful" }
            assertEquals("Hello!", response.content) { "Content should match agent result" }
            assertEquals(listOf("calculator"), response.toolsUsed) { "Tools used should be forwarded" }
            assertNull(response.errorMessage) { "Error message should be null on success" }
            assertEquals(true, response.grounded) { "grounded should be promoted to top-level response field" }
            assertEquals(1, response.verifiedSourceCount) { "verifiedSourceCount should be promoted to top-level response field" }
            assertNull(response.blockReason) { "blockReason should remain null when absent from metadata" }
            assertEquals(true, response.metadata["grounded"]) { "Metadata should be forwarded in response" }
        }

        @Test
        fun `map request fields to AgentCommand correctly해야 한다`() = runTest {
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
            assertEquals("anonymous", captured.userId) { "userId should be 'anonymous' without JWT auth" }
            assertEquals("value", captured.metadata["key"]) { "커스텀 메타데이터 전달" }
            assertEquals("web", captured.metadata["channel"]) { "channel=web 포함" }
            assertEquals("default", captured.metadata["tenantId"]) { "tenantId 포함" }
            assertTrue(captured.metadata.containsKey("sessionId")) { "sessionId 자동 생성" }
            assertEquals(ResponseFormat.JSON, captured.responseFormat) { "responseFormat should be forwarded" }
            assertEquals("""{"type":"object"}""", captured.responseSchema) { "responseSchema should be forwarded" }
        }

        @Test
        fun `preserve sessionId in metadata해야 한다`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val request = ChatRequest(
                message = "내 이름은 테스터야",
                metadata = mapOf("sessionId" to "test-session-123")
            )
            controller.chat(request, exchange)

            val captured = commandSlot.captured
            assertEquals("test-session-123", captured.metadata["sessionId"]) {
                "sessionId from request metadata must be preserved in AgentCommand metadata"
            }
        }

        @Test
        fun `not provided일 때 use default system prompt해야 한다`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"), exchange)

            assertTrue(commandSlot.captured.systemPrompt.contains("helpful AI assistant")) {
                "Default system prompt should contain 'helpful AI assistant'"
            }
        }

        @Test
        fun `default persona lookup fails일 때 fallback to hardcoded prompt해야 한다`() = runTest {
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
        fun `not provided일 때 use default metadata해야 한다`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"), exchange)

            val meta = commandSlot.captured.metadata
            assertEquals("web", meta["channel"]) { "channel=web 포함" }
            assertEquals("default", meta["tenantId"]) { "tenantId=default 포함" }
            assertTrue(meta.containsKey("sessionId")) { "sessionId 자동 생성" }
        }

        @Test
        fun `no intent matches일 때 mark controller intent resolution attempt해야 한다`() = runTest {
            val intentResolver = mockk<IntentResolver>()
            val controllerWithIntent = ChatController(agentExecutor = agentExecutor, intentResolver = intentResolver)
            val commandSlot = slot<AgentCommand>()
            coEvery { intentResolver.resolve(any(), any()) } returns null
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controllerWithIntent.chat(ChatRequest(message = "hello"), exchange)

            assertEquals(true, commandSlot.captured.metadata[IntentResolver.METADATA_INTENT_RESOLUTION_ATTEMPTED]) {
                "Controller should mark that intent resolution already ran"
            }
            assertTrue(commandSlot.captured.metadata.containsKey(IntentResolver.METADATA_INTENT_RESOLUTION_DURATION_MS)) {
                "Controller should preserve intent resolution duration for downstream stage timing"
            }
        }

        @Test
        fun `defer loading conversation history until intent resolver accesses it해야 한다`() = runTest {
            val intentResolver = mockk<IntentResolver>()
            val memoryStore = mockk<MemoryStore>()
            val controllerWithIntent = ChatController(
                agentExecutor = agentExecutor,
                intentResolver = intentResolver,
                memoryStore = memoryStore
            )
            coEvery { intentResolver.resolve(any(), any()) } returns null
            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            controllerWithIntent.chat(
                ChatRequest(message = "hello", metadata = mapOf("sessionId" to "session-1")),
                exchange
            )

            verify(exactly = 0) { memoryStore.get(any()) }
        }

        @Test
        fun `not explicitly provided일 때 inject requester email from exchange userEmail해야 한다`() = runTest {
            val exchangeWithEmail = mockExchange(attributes = mutableMapOf("resolvedTenantId" to "default", "userEmail" to "web@example.com"))
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "안녕"), exchangeWithEmail)

            assertEquals("web@example.com", commandSlot.captured.metadata["requesterEmail"]) {
                "requesterEmail should be injected from authenticated exchange context"
            }
            assertEquals("web@example.com", commandSlot.captured.metadata["userEmail"]) {
                "userEmail should be injected from authenticated exchange context"
            }
        }

        @Test
        fun `metadata not explicitly provided일 때 inject accountId from exchange해야 한다`() = runTest {
            val exchangeWithAccount = mockExchange(
                attributes = mutableMapOf("resolvedTenantId" to "default", "userAccountId" to "acct-001")
            )
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "안녕"), exchangeWithAccount)

            assertEquals("acct-001", commandSlot.captured.metadata["requesterAccountId"]) {
                "requesterAccountId should be injected from authenticated exchange context"
            }
            assertEquals("acct-001", commandSlot.captured.metadata["accountId"]) {
                "accountId should be injected from authenticated exchange context"
            }
        }

        @Test
        fun `persona resolves a template일 때 attach linked prompt template metadata해야 한다`() = runTest {
            val personaStore = mockk<PersonaStore>()
            val promptTemplateStore = mockk<PromptTemplateStore>()
            val linkedController = ChatController(
                agentExecutor = agentExecutor,
                personaStore = personaStore,
                promptTemplateStore = promptTemplateStore
            )
            val commandSlot = slot<AgentCommand>()

            every { personaStore.get("support") } returns Persona(
                id = "support",
                name = "Support",
                systemPrompt = "fallback",
                promptTemplateId = "template-support"
            )
            every { promptTemplateStore.getActiveVersion("template-support") } returns PromptVersion(
                id = "version-7",
                templateId = "template-support",
                version = 7,
                content = "Template prompt",
                status = VersionStatus.ACTIVE
            )
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            linkedController.chat(ChatRequest(message = "hello", personaId = "support"), exchange)

            assertEquals("template-support", commandSlot.captured.metadata["promptTemplateId"]) {
                "Linked prompt template id should be captured in metadata"
            }
            assertEquals("version-7", commandSlot.captured.metadata["promptVersionId"]) {
                "Active version id should be captured in metadata"
            }
            assertEquals(7, commandSlot.captured.metadata["promptVersion"]) {
                "Active version number should be captured in metadata"
            }
        }

        @Test
        fun `prefer resolved tenantId exchange attribute over request header해야 한다`() = runTest {
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
        fun `tenant header mismatches resolved tenant context일 때 reject request해야 한다`() = runTest {
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
        fun `tenant context is missing일 때 reject request해야 한다`() = runTest {
            val ex = try {
                controller.chat(ChatRequest(message = "hello"), mockExchange(attributes = mutableMapOf()))
                throw AssertionError("Missing tenant context should throw ServerWebInputException")
            } catch (e: ServerWebInputException) {
                e
            }
            assertTrue(ex.reason?.contains("Missing tenant context") == true) {
                "Requests without tenant context should fail-close"
            }
        }

        @Test
        fun `tenant header is provided일 때 allow request해야 한다`() = runTest {
            val headers = HttpHeaders()
            headers.add("X-Tenant-Id", "tenant-secure")
            val tenantExchange = mockExchange(attributes = mutableMapOf(), headers = headers)

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"), tenantExchange)

            assertEquals("tenant-secure", commandSlot.captured.metadata["tenantId"]) {
                "Tenant header should be accepted when no resolved tenant exists"
            }
        }

        @Test
        fun `error message and appropriate HTTP status로 return failure response해야 한다`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.failure(
                errorMessage = "Rate limit exceeded",
                errorCode = AgentErrorCode.RATE_LIMITED
            )

            val entity = controller.chat(ChatRequest(message = "hello"), exchange)

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, entity.statusCode) { "Rate limited should return HTTP 429" }
            val response = entity.body!!
            assertFalse(response.success) { "Response should indicate failure" }
            assertNull(response.content) { "Content should be null on failure" }
            assertEquals("Rate limit exceeded", response.errorMessage) { "Error message should be forwarded" }
        }

        @Test
        fun `forward model in response해야 한다`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            val entity = controller.chat(ChatRequest(message = "hi", model = "gemini-2.5-flash"), exchange)

            assertEquals("gemini-2.5-flash", entity.body!!.model) { "Model should be forwarded in response" }
        }

        @Test
        fun `present일 때 resolve userId from exchange attributes해야 한다`() = runTest {
            val authExchange = mockExchange(mutableMapOf("userId" to "jwt-user-1", "resolvedTenantId" to "default"))

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello", userId = "request-user"), authExchange)

            assertEquals("jwt-user-1", commandSlot.captured.userId) {
                "Should prefer JWT userId from exchange over request body userId"
            }
        }

        @Test
        fun `exchange has no auth일 때 ignore request userId and use anonymous해야 한다`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello", userId = "request-user"), exchange)

            assertEquals("anonymous", commandSlot.captured.userId) {
                "Should use 'anonymous' when no JWT present to prevent userId spoofing"
            }
        }

        @Test
        fun `no userId available일 때 fallback to anonymous해야 한다`() = runTest {
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
        fun `message event type로 return ServerSentEvents해야 한다`() = runTest {
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
        fun `convert tool markers to SSE events해야 한다`() = runTest {
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
        fun `always emit done event at the end해야 한다`() = runTest {
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
        fun `map streaming request fields to AgentCommand해야 한다`() = runTest {
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
            assertEquals("anonymous", captured.userId) { "userId should be 'anonymous' without JWT auth" }
        }

        @Test
        fun `optional streaming fields에 대해 use default values해야 한다`() = runTest {
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
            assertEquals("web", captured.metadata["channel"]) { "channel=web 포함" }
            assertEquals("default", captured.metadata["tenantId"]) { "tenantId 포함" }
            assertTrue(captured.metadata.containsKey("sessionId")) { "sessionId 자동 생성" }
        }
    }

    @Nested
    inner class StreamEventMarkerTest {

        @Test
        fun `toolStart은(는) create proper marker해야 한다`() {
            val marker = StreamEventMarker.toolStart("calculator")

            assertTrue(StreamEventMarker.isMarker(marker)) { "Should be recognized as marker" }
            val parsed = StreamEventMarker.parse(marker)
            assertNotNull(parsed) { "Should be parseable" }
            assertEquals("tool_start", parsed!!.first) { "Event type should be tool_start" }
            assertEquals("calculator", parsed.second) { "Tool name should be calculator" }
        }

        @Test
        fun `toolEnd은(는) create proper marker해야 한다`() {
            val marker = StreamEventMarker.toolEnd("web_search")

            assertTrue(StreamEventMarker.isMarker(marker)) { "Should be recognized as marker" }
            val parsed = StreamEventMarker.parse(marker)
            assertNotNull(parsed) { "Should be parseable" }
            assertEquals("tool_end", parsed!!.first) { "Event type should be tool_end" }
            assertEquals("web_search", parsed.second) { "Tool name should be web_search" }
        }

        @Test
        fun `regular text은(는) not be a marker해야 한다`() {
            assertFalse(StreamEventMarker.isMarker("Hello world")) {
                "Regular text should not be a marker"
            }
            assertNull(StreamEventMarker.parse("Hello world")) {
                "Regular text should not be parseable as marker"
            }
        }

        @Test
        fun `empty string은(는) not be a marker해야 한다`() {
            assertFalse(StreamEventMarker.isMarker("")) { "Empty string should not be a marker" }
            assertNull(StreamEventMarker.parse("")) { "Empty string should not be parseable" }
        }
    }

    @Nested
    inner class ErrorCodeToHttpStatus {

        @Test
        fun `map error codes to correct HTTP status codes해야 한다`() {
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, mapErrorCodeToStatus(AgentErrorCode.RATE_LIMITED)) {
                "RATE_LIMITED should map to 429"
            }
            assertEquals(HttpStatus.FORBIDDEN, mapErrorCodeToStatus(AgentErrorCode.GUARD_REJECTED)) {
                "GUARD_REJECTED should map to 403"
            }
            assertEquals(HttpStatus.FORBIDDEN, mapErrorCodeToStatus(AgentErrorCode.HOOK_REJECTED)) {
                "HOOK_REJECTED should map to 403"
            }
            assertEquals(HttpStatus.GATEWAY_TIMEOUT, mapErrorCodeToStatus(AgentErrorCode.TIMEOUT)) {
                "TIMEOUT should map to 504"
            }
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, mapErrorCodeToStatus(AgentErrorCode.CIRCUIT_BREAKER_OPEN)) {
                "CIRCUIT_BREAKER_OPEN should map to 503"
            }
            assertEquals(HttpStatus.BAD_REQUEST, mapErrorCodeToStatus(AgentErrorCode.CONTEXT_TOO_LONG)) {
                "CONTEXT_TOO_LONG should map to 400"
            }
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, mapErrorCodeToStatus(AgentErrorCode.OUTPUT_GUARD_REJECTED)) {
                "OUTPUT_GUARD_REJECTED should map to 422 (output rejected by post-processing guard)"
            }
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, mapErrorCodeToStatus(AgentErrorCode.OUTPUT_TOO_SHORT)) {
                "OUTPUT_TOO_SHORT should map to 422 (output rejected by post-processing guard)"
            }
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapErrorCodeToStatus(AgentErrorCode.UNKNOWN)) {
                "UNKNOWN should map to 500"
            }
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapErrorCodeToStatus(null)) {
                "null error code should map to 500"
            }
        }
    }

    @Nested
    inner class RequestDefaults {

        @Test
        fun `ChatRequest은(는) have sensible defaults해야 한다`() {
            val request = ChatRequest(message = "test")

            assertNull(request.model) { "model should default to null" }
            assertNull(request.systemPrompt) { "systemPrompt should default to null" }
            assertNull(request.userId) { "userId should default to null" }
            assertNull(request.metadata) { "metadata should default to null" }
            assertNull(request.responseFormat) { "responseFormat should default to null" }
            assertNull(request.responseSchema) { "responseSchema should default to null" }
        }

        @Test
        fun `ChatResponse은(는) include all fields해야 한다`() {
            val response = ChatResponse(
                content = "result",
                success = true,
                model = "gemini",
                toolsUsed = listOf("calc"),
                errorMessage = null,
                grounded = true,
                verifiedSourceCount = 2,
                blockReason = "policy_denied",
                metadata = mapOf("grounded" to true)
            )

            assertEquals("result", response.content) { "content should match" }
            assertTrue(response.success) { "success should be true" }
            assertEquals("gemini", response.model) { "model should match" }
            assertEquals(listOf("calc"), response.toolsUsed) { "toolsUsed should match" }
            assertNull(response.errorMessage) { "errorMessage should be null" }
            assertEquals(true, response.grounded) { "grounded should match" }
            assertEquals(2, response.verifiedSourceCount) { "verifiedSourceCount should match" }
            assertEquals("policy_denied", response.blockReason) { "blockReason should match" }
            assertEquals(true, response.metadata["grounded"]) { "metadata should match" }
        }
    }

    @Nested
    inner class MultimodalToggle {

        @Test
        fun `multimodal is enabled (default)일 때 resolve mediaUrls해야 한다`() = runTest {
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
        fun `multimodal is disabled일 때 ignore mediaUrls해야 한다`() = runTest {
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
        fun `multimodal is disabled일 때 ignore mediaUrls in streaming해야 한다`() = runTest {
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
        fun `no mediaUrls provided regardless of toggle일 때 pass empty media해야 한다`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            controller.chat(ChatRequest(message = "hello"), exchange)

            assertTrue(commandSlot.captured.media.isEmpty()) {
                "Should have no media when mediaUrls not provided"
            }
        }

        @Test
        fun `bad request exception로 reject invalid media mime type해야 한다`() = runTest {
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
        fun `bad request exception로 reject invalid media url해야 한다`() = runTest {
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
        fun `bad request exception로 reject relative media url해야 한다`() = runTest {
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
        fun `reject non-http media url scheme해야 한다`() = runTest {
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
        fun `accept trimmed https media url and mime type해야 한다`() = runTest {
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
        fun `reject absolute https url without host해야 한다`() = runTest {
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
