package com.arc.reactor.controller

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.memory.ConversationMemory
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.memory.SessionSummary
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import com.arc.reactor.auth.JwtAuthWebFilter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

class SessionControllerTest {

    private lateinit var memoryStore: MemoryStore
    private lateinit var chatModelProvider: ChatModelProvider
    private lateinit var controller: SessionController
    private lateinit var exchange: ServerWebExchange

    @BeforeEach
    fun setup() {
        memoryStore = mockk()
        chatModelProvider = mockk()
        controller = SessionController(memoryStore, chatModelProvider)
        exchange = mockk()
        every { exchange.attributes } returns mutableMapOf()
    }

    @Nested
    inner class ListSessions {

        @Test
        fun `should return empty list when no sessions exist`() = runTest {
            every { memoryStore.listSessions() } returns emptyList()

            val result = controller.listSessions(exchange)

            assertTrue(result.isEmpty()) { "Expected empty list, got ${result.size} sessions" }
        }

        @Test
        fun `should return session summaries with correct fields`() = runTest {
            val now = Instant.parse("2026-02-08T12:00:00Z")
            every { memoryStore.listSessions() } returns listOf(
                SessionSummary("session-1", 5, now, "Hello, how are you?")
            )

            val result = controller.listSessions(exchange)

            assertEquals(1, result.size) { "Expected 1 session" }
            val session = result[0]
            assertEquals("session-1", session.sessionId) { "Session ID should match" }
            assertEquals(5, session.messageCount) { "Message count should match" }
            assertEquals(now.toEpochMilli(), session.lastActivity) { "Last activity should be epoch millis" }
            assertEquals("Hello, how are you?", session.preview) { "Preview should match" }
        }

        @Test
        fun `should return multiple sessions`() = runTest {
            val now = Instant.now()
            every { memoryStore.listSessions() } returns listOf(
                SessionSummary("s-1", 3, now, "First"),
                SessionSummary("s-2", 1, now.minusSeconds(60), "Second")
            )

            val result = controller.listSessions(exchange)

            assertEquals(2, result.size) { "Expected 2 sessions" }
            assertEquals("s-1", result[0].sessionId) { "First session should be s-1" }
            assertEquals("s-2", result[1].sessionId) { "Second session should be s-2" }
        }
    }

    @Nested
    inner class GetSession {

        @Test
        fun `should return 404 when session does not exist`() = runTest {
            every { memoryStore.get("nonexistent") } returns null

            val response = controller.getSession("nonexistent", exchange)

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404 for missing session" }
        }

        @Test
        fun `should return session messages with correct fields`() = runTest {
            val now = Instant.parse("2026-02-08T12:00:00Z")
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "Hello!", now),
                Message(MessageRole.ASSISTANT, "Hi there!", now.plusSeconds(2))
            )

            val response = controller.getSession("session-1", exchange)

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            val body = response.body!!
            assertEquals("session-1", body.sessionId) { "Session ID should match" }
            assertEquals(2, body.messages.size) { "Should have 2 messages" }
        }

        @Test
        fun `should map message roles to lowercase`() = runTest {
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "Hello!"),
                Message(MessageRole.ASSISTANT, "Hi!"),
                Message(MessageRole.SYSTEM, "System msg")
            )

            val body = controller.getSession("session-1", exchange).body!!

            assertEquals("user", body.messages[0].role) { "USER should map to 'user'" }
            assertEquals("assistant", body.messages[1].role) { "ASSISTANT should map to 'assistant'" }
            assertEquals("system", body.messages[2].role) { "SYSTEM should map to 'system'" }
        }

        @Test
        fun `should return timestamps as epoch millis`() = runTest {
            val now = Instant.parse("2026-02-08T12:00:00Z")
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "Hello!", now)
            )

            val body = controller.getSession("session-1", exchange).body!!

            assertEquals(now.toEpochMilli(), body.messages[0].timestamp) { "Timestamp should be epoch millis" }
        }
    }

    @Nested
    inner class ExportSession {

        @Test
        fun `should export session as JSON by default`() = runTest {
            val now = Instant.parse("2026-02-08T12:00:00Z")
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "Hello!", now),
                Message(MessageRole.ASSISTANT, "Hi!", now.plusSeconds(1))
            )

            val response = controller.exportSession("session-1", "json", exchange)

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            val body = response.body as Map<*, *>
            assertEquals("session-1", body["sessionId"]) { "Should include sessionId" }
            val messages = body["messages"] as List<*>
            assertEquals(2, messages.size) { "Should include 2 messages" }
        }

        @Test
        fun `should export session as markdown`() = runTest {
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "Hello!"),
                Message(MessageRole.ASSISTANT, "Hi there!")
            )

            val response = controller.exportSession("session-1", "markdown", exchange)

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            val body = response.body as String
            assertTrue(body.contains("# Conversation: session-1")) { "Should have markdown header" }
            assertTrue(body.contains("## user")) { "Should have user role header" }
            assertTrue(body.contains("Hello!")) { "Should include message content" }
        }

        @Test
        fun `should return 404 for nonexistent session`() = runTest {
            every { memoryStore.get("nonexistent") } returns null

            val response = controller.exportSession("nonexistent", "json", exchange)

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }
    }

    @Nested
    inner class DeleteSession {

        @Test
        fun `should return 204 on successful deletion`() = runTest {
            every { memoryStore.remove("session-1") } returns Unit

            val response = controller.deleteSession("session-1", exchange)

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "Should return 204 No Content" }
        }

        @Test
        fun `should call memoryStore remove with correct sessionId`() = runTest {
            every { memoryStore.remove(any()) } returns Unit

            controller.deleteSession("target-session", exchange)

            verify(exactly = 1) { memoryStore.remove("target-session") }
        }

        @Test
        fun `should return 204 even for nonexistent session`() = runTest {
            every { memoryStore.remove("nonexistent") } returns Unit

            val response = controller.deleteSession("nonexistent", exchange)

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "DELETE should be idempotent" }
        }
    }

    @Nested
    inner class SessionOwnership {

        @Test
        fun `should return 403 when authenticated user does not own session`() = runTest {
            val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ID_ATTRIBUTE to "user-1")
            every { exchange.attributes } returns attrs
            every { memoryStore.getSessionOwner("session-1") } returns "user-2"

            val response = controller.getSession("session-1", exchange)

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should deny access to other user's session" }
        }

        @Test
        fun `should allow access when user owns session`() = runTest {
            val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ID_ATTRIBUTE to "user-1")
            every { exchange.attributes } returns attrs
            every { memoryStore.getSessionOwner("session-1") } returns "user-1"
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns emptyList()

            val response = controller.getSession("session-1", exchange)

            assertEquals(HttpStatus.OK, response.statusCode) { "Should allow access to own session" }
        }

        @Test
        fun `should allow access when session has no owner`() = runTest {
            val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ID_ATTRIBUTE to "user-1")
            every { exchange.attributes } returns attrs
            every { memoryStore.getSessionOwner("session-1") } returns null
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns emptyList()

            val response = controller.getSession("session-1", exchange)

            assertEquals(HttpStatus.OK, response.statusCode) { "Should allow access when no owner recorded" }
        }

        @Test
        fun `should deny access when session owner is anonymous`() = runTest {
            val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ID_ATTRIBUTE to "user-1")
            every { exchange.attributes } returns attrs
            every { memoryStore.getSessionOwner("session-1") } returns "anonymous"

            val response = controller.getSession("session-1", exchange)

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Anonymous-owned sessions should not be accessible by authenticated users"
            }
        }

        @Test
        fun `should return 403 on delete when user does not own session`() = runTest {
            val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ID_ATTRIBUTE to "user-1")
            every { exchange.attributes } returns attrs
            every { memoryStore.getSessionOwner("session-1") } returns "user-2"

            val response = controller.deleteSession("session-1", exchange)

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should deny deletion of other user's session" }
            verify(exactly = 0) { memoryStore.remove(any()) }
        }

        @Test
        fun `should skip ownership check when auth is disabled`() = runTest {
            // No userId attribute = auth disabled
            every { exchange.attributes } returns mutableMapOf()
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns emptyList()

            val response = controller.getSession("session-1", exchange)

            assertEquals(HttpStatus.OK, response.statusCode) { "Should allow access when auth is disabled" }
        }
    }

    @Nested
    inner class ListModels {

        @Test
        fun `should return available providers`() = runTest {
            every { chatModelProvider.availableProviders() } returns setOf("gemini", "openai")
            every { chatModelProvider.defaultProvider() } returns "gemini"

            val result = controller.listModels()

            assertEquals(2, result.models.size) { "Should have 2 models" }
            val names = result.models.map { it.name }.toSet()
            assertTrue(names.contains("gemini")) { "Should include gemini" }
            assertTrue(names.contains("openai")) { "Should include openai" }
        }

        @Test
        fun `should mark default provider correctly`() = runTest {
            every { chatModelProvider.availableProviders() } returns setOf("gemini", "openai")
            every { chatModelProvider.defaultProvider() } returns "gemini"

            val result = controller.listModels()

            val gemini = result.models.first { it.name == "gemini" }
            val openai = result.models.first { it.name == "openai" }
            assertTrue(gemini.isDefault) { "gemini should be marked as default" }
            assertFalse(openai.isDefault) { "openai should not be marked as default" }
        }

        @Test
        fun `should return defaultModel field`() = runTest {
            every { chatModelProvider.availableProviders() } returns setOf("gemini")
            every { chatModelProvider.defaultProvider() } returns "gemini"

            val result = controller.listModels()

            assertEquals("gemini", result.defaultModel) { "Default model should be gemini" }
        }

        @Test
        fun `should handle single provider`() = runTest {
            every { chatModelProvider.availableProviders() } returns setOf("anthropic")
            every { chatModelProvider.defaultProvider() } returns "anthropic"

            val result = controller.listModels()

            assertEquals(1, result.models.size) { "Should have 1 model" }
            assertTrue(result.models[0].isDefault) { "Single model should be default" }
        }
    }
}
