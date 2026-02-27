package com.arc.reactor.line

import com.arc.reactor.line.service.LineMessagingService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class LineMessagingServiceTest {

    private val objectMapper = jacksonObjectMapper()
    private lateinit var mockServer: MockWebServer
    private lateinit var service: LineMessagingService

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val webClient = WebClient.builder()
            .baseUrl(mockServer.url("/").toString())
            .defaultHeader("Authorization", "Bearer test-channel-token")
            .defaultHeader("Content-Type", "application/json")
            .build()

        service = LineMessagingService(channelToken = "test-channel-token", webClient = webClient)
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Nested
    inner class ReplyMessage {

        @Test
        fun `returns true on successful reply`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"sentMessages":[{"id":"msg-1","quoteToken":"qt-1"}]}""")
            )

            val result = service.replyMessage("reply-token-abc", "Hello from LINE!")

            result shouldBe true
        }

        @Test
        fun `sends correct replyToken and text in request body`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"sentMessages":[]}""")
            )

            service.replyMessage("tok-12345", "Hi there!")

            val request = mockServer.takeRequest()
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("replyToken").asText() shouldBe "tok-12345"
            body.path("messages").get(0).path("text").asText() shouldBe "Hi there!"
        }

        @Test
        fun `sends message type as text`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"sentMessages":[]}""")
            )

            service.replyMessage("tok-1", "test")

            val request = mockServer.takeRequest()
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("messages").get(0).path("type").asText() shouldBe "text"
        }

        @Test
        fun `calls the reply endpoint`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"sentMessages":[]}""")
            )

            service.replyMessage("tok-1", "hello")

            val request = mockServer.takeRequest()
            request.path shouldBe "/reply"
        }

        @Test
        fun `returns false on HTTP 400 error`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"message":"Invalid reply token","details":[]}""")
            )

            val result = service.replyMessage("expired-token", "text")

            result shouldBe false
        }

        @Test
        fun `returns false on HTTP 500 error`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(500))

            val result = service.replyMessage("tok-1", "text")

            result shouldBe false
        }

        @Test
        fun `returns false when server is unreachable`() = runTest {
            mockServer.shutdown()

            val result = service.replyMessage("tok-1", "text")

            result shouldBe false
        }
    }

    @Nested
    inner class PushMessage {

        @Test
        fun `sends push message to target user`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"sentMessages":[{"id":"msg-2","quoteToken":"qt-2"}]}""")
            )

            service.pushMessage("U123456", "Push notification text")

            val request = mockServer.takeRequest()
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("to").asText() shouldBe "U123456"
            body.path("messages").get(0).path("text").asText() shouldBe "Push notification text"
        }

        @Test
        fun `calls the push endpoint`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"sentMessages":[]}""")
            )

            service.pushMessage("U123", "test")

            val request = mockServer.takeRequest()
            request.path shouldBe "/push"
        }

        @Test
        fun `sends message type as text in push body`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"sentMessages":[]}""")
            )

            service.pushMessage("G456", "group message")

            val request = mockServer.takeRequest()
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("messages").get(0).path("type").asText() shouldBe "text"
        }

        @Test
        fun `sends to group ID when group is the target`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"sentMessages":[]}""")
            )

            service.pushMessage("G789", "group-level message")

            val request = mockServer.takeRequest()
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("to").asText() shouldBe "G789"
        }

        @Test
        fun `does not throw on HTTP error for push`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(400))

            // pushMessage swallows errors and only logs them
            service.pushMessage("U123", "fallback message")
        }
    }
}
