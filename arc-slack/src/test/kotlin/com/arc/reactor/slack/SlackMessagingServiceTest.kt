package com.arc.reactor.slack

import com.arc.reactor.slack.service.SlackMessagingService
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

class SlackMessagingServiceTest {

    private val objectMapper = jacksonObjectMapper()
    private lateinit var mockServer: MockWebServer
    private lateinit var service: SlackMessagingService

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val webClient = WebClient.builder()
            .baseUrl(mockServer.url("/").toString())
            .defaultHeader("Authorization", "Bearer test-token")
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .build()

        val responseWebClient = WebClient.builder().build()
        service = SlackMessagingService(
            botToken = "test-token",
            maxApiRetries = 2,
            retryDefaultDelayMs = 10,
            webClient = webClient,
            responseWebClient = responseWebClient
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Nested
    inner class SendMessage {

        @Test
        fun `sends message successfully`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"1234.5678","channel":"C123"}""")
            )

            val result = service.sendMessage("C123", "Hello")

            result.ok shouldBe true
            result.ts shouldBe "1234.5678"

            val request = mockServer.takeRequest()
            request.path shouldBe "/chat.postMessage"
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("channel").asText() shouldBe "C123"
            body.path("text").asText() shouldBe "Hello"
        }

        @Test
        fun `sends thread reply`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"1234.9999","channel":"C123"}""")
            )

            val result = service.sendMessage("C123", "Reply", "1234.5678")

            result.ok shouldBe true

            val request = mockServer.takeRequest()
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("thread_ts").asText() shouldBe "1234.5678"
        }

        @Test
        fun `handles API error response`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":false,"error":"channel_not_found"}""")
            )

            val result = service.sendMessage("INVALID", "Hello")

            result.ok shouldBe false
            result.error shouldBe "channel_not_found"
        }

        @Test
        fun `retries on rate limit and succeeds`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":false,"error":"ratelimited"}""")
            )
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"1234.7777","channel":"C123"}""")
            )

            val result = service.sendMessage("C123", "Hello with retry")

            result.ok shouldBe true
            result.ts shouldBe "1234.7777"
            mockServer.requestCount shouldBe 2
        }

        @Test
        fun `retries on server error and succeeds`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(503))
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"1234.8888","channel":"C123"}""")
            )

            val result = service.sendMessage("C123", "Hello after 5xx")

            result.ok shouldBe true
            result.ts shouldBe "1234.8888"
            mockServer.requestCount shouldBe 2
        }
    }

    @Nested
    inner class AddReaction {

        @Test
        fun `adds reaction successfully`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true}""")
            )

            val result = service.addReaction("C123", "1234.5678", "thumbsup")

            result.ok shouldBe true

            val request = mockServer.takeRequest()
            request.path shouldBe "/reactions.add"
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("name").asText() shouldBe "thumbsup"
        }
    }

    @Nested
    inner class ResponseUrl {

        @Test
        fun `sends callback to response_url successfully`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(200))

            val ok = service.sendResponseUrl(
                responseUrl = mockServer.url("/response").toString(),
                text = "Done",
                responseType = "ephemeral"
            )

            ok shouldBe true

            val request = mockServer.takeRequest()
            request.path shouldBe "/response"
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("response_type").asText() shouldBe "ephemeral"
            body.path("text").asText() shouldBe "Done"
        }
    }
}
