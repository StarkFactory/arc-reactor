package com.arc.reactor.teams

import com.arc.reactor.teams.config.TeamsProperties
import com.arc.reactor.teams.config.TeamsWebhookClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TeamsWebhookClientTest {

    private val server = MockWebServer()
    private val client = TeamsWebhookClient(TeamsProperties(enabled = true), ssrfProtectionEnabled = false)

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Nested
    inner class MessageCardFormat {

        @Test
        fun `POST request with MessageCard JSON body를 전송한다`() {
            server.enqueue(MockResponse().setResponseCode(200))

            val url = server.url("/webhook").toString()
            client.sendMessage(url, "Hello from Arc Reactor")

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method, "Request method must be POST")

            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"@type\": \"MessageCard\""),
                "Body must contain MessageCard @type")
            assertTrue(body.contains("\"@context\": \"http://schema.org/extensions\""),
                "Body must contain schema.org @context")
            assertTrue(body.contains("\"themeColor\": \"0078D4\""),
                "Body must include Teams blue themeColor")
            assertTrue(body.contains("Hello from Arc Reactor"),
                "Body must include the actual message text")
        }

        @Test
        fun `Content-Type application json header를 전송한다`() {
            server.enqueue(MockResponse().setResponseCode(200))

            val url = server.url("/webhook").toString()
            client.sendMessage(url, "Test message")

            val recorded = server.takeRequest()
            assertEquals("application/json", recorded.getHeader("Content-Type"),
                "Content-Type header must be application/json")
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `throw when server returns HTTP 400하지 않는다`() {
            server.enqueue(MockResponse().setResponseCode(400))

            val url = server.url("/webhook").toString()
            // not throw — error is logged as warn해야 합니다
            client.sendMessage(url, "Test message")
        }

        @Test
        fun `throw when server returns HTTP 500하지 않는다`() {
            server.enqueue(MockResponse().setResponseCode(500))

            val url = server.url("/webhook").toString()
            client.sendMessage(url, "Test message")
        }

        @Test
        fun `does not throw when webhook URL은(는) unreachable이다`() {
            // Port 1 is not in use — connection은(는) be refused해야 합니다
            client.sendMessage("http://localhost:1/webhook", "Test message")
        }
    }

    @Nested
    inner class Truncation {

        @Test
        fun `message at 3000 characters and appends ellipsis를 잘라낸다`() {
            server.enqueue(MockResponse().setResponseCode(200))

            val longMessage = "A".repeat(4000)
            val url = server.url("/webhook").toString()
            client.sendMessage(url, longMessage)

            val recorded = server.takeRequest()
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("A".repeat(3000)),
                "Body must contain the first 3000 characters")
            assertTrue(body.contains("\\n..."),
                "Body must contain truncation ellipsis after 3000 chars")
        }

        @Test
        fun `truncate messages shorter than 3000 characters하지 않는다`() {
            server.enqueue(MockResponse().setResponseCode(200))

            val shortMessage = "Short message"
            val url = server.url("/webhook").toString()
            client.sendMessage(url, shortMessage)

            val recorded = server.takeRequest()
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("Short message"),
                "Short message should be sent without modification")
            assertTrue(!body.contains("\\n..."),
                "No ellipsis should appear for short messages")
        }

        @Test
        fun `message of exactly 3000 characters without truncation를 처리한다`() {
            server.enqueue(MockResponse().setResponseCode(200))

            val exactMessage = "B".repeat(3000)
            val url = server.url("/webhook").toString()
            client.sendMessage(url, exactMessage)

            val recorded = server.takeRequest()
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("B".repeat(3000)),
                "Exactly-3000-char message must be sent in full")
            assertTrue(!body.contains("\\n..."),
                "No ellipsis for message at exactly the limit")
        }
    }
}
