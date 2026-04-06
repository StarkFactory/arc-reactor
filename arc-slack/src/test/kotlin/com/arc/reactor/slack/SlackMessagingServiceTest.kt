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

/**
 * [SlackMessagingService]의 Slack API 메시징 테스트.
 *
 * MockWebServer를 사용하여 메시지 전송, 스레드 답장, API 에러 처리,
 * 429/5xx 재시도, 리액션 추가, response_url 콜백 등을 검증한다.
 */
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
            responseWebClient = responseWebClient,
            allowedResponseHosts = setOf("hooks.slack.com", "slack.com", "localhost", "127.0.0.1")
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Nested
    inner class SendMessage {

        @Test
        fun `message successfully를 전송한다`() = runTest {
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
        fun `thread reply를 전송한다`() = runTest {
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
        fun `API error response를 처리한다`() = runTest {
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
        fun `on rate limit and succeeds를 재시도한다`() = runTest {
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
        fun `on server error and succeeds를 재시도한다`() = runTest {
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
        fun `reaction successfully를 추가한다`() = runTest {
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
        fun `callback to response_url successfully를 전송한다`() = runTest {
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

    @Nested
    inner class AssistantThreadStatus {

        @Test
        fun `상태를 성공적으로 설정한다`() = runTest {
            mockServer.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}"""))

            val result = service.setAssistantThreadStatus("D123", "1234.5678", "생각하고 있어요...")
            result.ok shouldBe true

            val request = mockServer.takeRequest()
            request.path shouldBe "/assistant.threads.setStatus"
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("channel_id").asText() shouldBe "D123"
            body.path("thread_ts").asText() shouldBe "1234.5678"
            body.path("status").asText() shouldBe "생각하고 있어요..."
        }

        @Test
        fun `API 실패 시 ok=false를 반환한다`() = runTest {
            // maxApiRetries=2 → 3번 실패해야 최종 실패
            repeat(3) { mockServer.enqueue(MockResponse().setResponseCode(500)) }

            val result = service.setAssistantThreadStatus("D123", "1234.5678", "test")
            result.ok shouldBe false
        }
    }

    @Nested
    inner class AssistantSuggestedPrompts {

        @Test
        fun `추천 프롬프트를 성공적으로 설정한다`() = runTest {
            mockServer.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}"""))

            val prompts = listOf(
                mapOf("title" to "업무 질문", "message" to "오늘 할 일 알려줘"),
                mapOf("title" to "Jira 조회", "message" to "내 이슈 보여줘")
            )
            val result = service.setAssistantSuggestedPrompts("D123", "1234.5678", prompts)
            result.ok shouldBe true

            val request = mockServer.takeRequest()
            request.path shouldBe "/assistant.threads.setSuggestedPrompts"
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("prompts").size() shouldBe 2
            body.path("prompts")[0].path("title").asText() shouldBe "업무 질문"
        }
    }

    @Nested
    inner class AssistantThreadTitle {

        @Test
        fun `스레드 제목을 성공적으로 설정한다`() = runTest {
            mockServer.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}"""))

            val result = service.setAssistantThreadTitle("D123", "1234.5678", "Reactor 대화")
            result.ok shouldBe true

            val request = mockServer.takeRequest()
            request.path shouldBe "/assistant.threads.setTitle"
            val body = objectMapper.readTree(request.body.readUtf8())
            body.path("title").asText() shouldBe "Reactor 대화"
        }
    }
}
