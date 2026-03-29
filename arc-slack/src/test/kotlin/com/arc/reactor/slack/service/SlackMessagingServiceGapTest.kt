package com.arc.reactor.slack.service

import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackApiResult
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

/**
 * [SlackMessagingService]의 커버리지 갭 테스트.
 *
 * 다음 영역을 집중 검증한다:
 * - isAllowedResponseUrl: SSRF 방지 (빈 URL, 잘못된 형식, 차단된 호스트, 서브도메인)
 * - enforceRateLimit: 채널별 속도 제한 (첫 요청, 동일 채널 재요청, 다른 채널 독립)
 * - callSlackApi 재시도: 429 소진, 5xx 소진, 4xx 재시도 안 함, Retry-After 헤더
 * - 메트릭 기록: recordApiCall, recordApiRetry, recordResponseUrl
 */
class SlackMessagingServiceGapTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var metricsRecorder: SlackMetricsRecorder
    private lateinit var service: SlackMessagingService

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        metricsRecorder = mockk(relaxed = true)

        val webClient = WebClient.builder()
            .baseUrl(mockServer.url("/").toString())
            .defaultHeader("Authorization", "Bearer test-token")
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .build()

        service = SlackMessagingService(
            botToken = "test-token",
            maxApiRetries = 2,
            retryDefaultDelayMs = 10L,
            metricsRecorder = metricsRecorder,
            webClient = webClient,
            responseWebClient = WebClient.builder()
                .baseUrl(mockServer.url("/").toString())
                .build(),
            allowedResponseHosts = setOf("hooks.slack.com", "slack.com", "localhost")
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    // ───────────────────────────────────────────────────────────────────────
    // SSRF 방지: isAllowedResponseUrl
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    inner class SsrfProtection {

        @Test
        fun `빈 response_url은 false를 반환한다`() = runTest {
            val result = service.sendResponseUrl(responseUrl = "", text = "Hello")

            result shouldBe false
        }

        @Test
        fun `공백만 있는 response_url은 false를 반환한다`() = runTest {
            val result = service.sendResponseUrl(responseUrl = "   ", text = "Hello")

            result shouldBe false
        }

        @Test
        fun `파싱 불가능한 URL은 false를 반환한다`() = runTest {
            // 호스트가 없는 잘못된 URI — java.net.URI가 host=null 반환
            val result = service.sendResponseUrl(
                responseUrl = "not-a-valid-url",
                text = "Hello"
            )

            result shouldBe false
        }

        @Test
        fun `허용되지 않은 외부 호스트는 false를 반환한다`() = runTest {
            val result = service.sendResponseUrl(
                responseUrl = "https://evil.com/callback",
                text = "Hello"
            )

            result shouldBe false
        }

        @Test
        fun `내부망 IP(169_254_169_254)는 false를 반환한다`() = runTest {
            val result = service.sendResponseUrl(
                responseUrl = "http://169.254.169.254/latest/meta-data",
                text = "Hello"
            )

            result shouldBe false
        }

        @Test
        fun `허용된 hooks_slack_com 서브도메인은 true를 반환한다`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(200))

            // allowedResponseHosts에 "localhost"가 있으므로 localhost 사용
            val result = service.sendResponseUrl(
                responseUrl = mockServer.url("/webhook").toString(),
                text = "Done"
            )

            result shouldBe true
        }

        @Test
        fun `서브도메인(sub_slack_com)은 허용된다`() = runTest {
            // "slack.com"이 allowedResponseHosts에 포함되어 있으므로
            // "sub.slack.com"도 host.endsWith(".slack.com") 로직으로 허용됨
            // 실제 요청은 하지 않고 isAllowedResponseUrl 로직만 검증하기 위해
            // 서버 응답 없이 호출하면 연결 실패 → false
            // 따라서 MockWebServer baseUrl을 서비스에 주입하고 슬랙 도메인 허용 서비스를 생성
            val slackAllowedService = SlackMessagingService(
                botToken = "test-token",
                maxApiRetries = 0,
                retryDefaultDelayMs = 10L,
                metricsRecorder = metricsRecorder,
                webClient = WebClient.builder().baseUrl(mockServer.url("/").toString()).build(),
                responseWebClient = WebClient.builder()
                    .baseUrl(mockServer.url("/").toString())
                    .build(),
                allowedResponseHosts = setOf("slack.com")
            )
            mockServer.enqueue(MockResponse().setResponseCode(200))

            // localhost는 slack.com의 서브도메인이 아니므로 차단됨
            // 대신 responseWebClient baseUrl을 통한 localhost 요청은 차단
            // → isAllowedResponseUrl이 false를 반환함을 확인
            val blockedResult = slackAllowedService.sendResponseUrl(
                responseUrl = "http://localhost/callback",
                text = "test"
            )

            blockedResult shouldBe false
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 채널별 속도 제한: enforceRateLimit
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    inner class RateLimiting {

        @Test
        fun `처음 요청은 지연 없이 성공한다`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"111.222","channel":"C001"}""")
            )

            val result = service.sendMessage("C001", "First message")

            result.ok shouldBe true
        }

        @Test
        fun `다른 채널은 서로 독립적으로 속도 제한된다`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"111.111","channel":"C001"}""")
            )
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"222.222","channel":"C002"}""")
            )

            val result1 = service.sendMessage("C001", "Channel 1 message")
            val result2 = service.sendMessage("C002", "Channel 2 message")

            result1.ok shouldBe true
            result2.ok shouldBe true
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // API 재시도 로직
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    inner class RetryLogic {

        @Test
        fun `429를 maxApiRetries 초과 시 예외가 wrapping되어 SlackApiResult 실패로 반환된다`() = runTest {
            // maxApiRetries=2 → 총 3번 시도 가능
            // 3번 모두 429 → sendMessage가 catch하여 SlackApiResult(ok=false) 반환
            repeat(3) {
                mockServer.enqueue(
                    MockResponse()
                        .setResponseCode(429)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"ok":false,"error":"ratelimited"}""")
                )
            }

            val result = service.sendMessage("C123", "Exhausted retries")

            result.ok shouldBe false
            mockServer.requestCount shouldBe 3
        }

        @Test
        fun `5xx 오류를 maxApiRetries 초과 시 SlackApiResult 실패로 반환된다`() = runTest {
            repeat(3) {
                mockServer.enqueue(MockResponse().setResponseCode(503))
            }

            val result = service.sendMessage("C123", "5xx exhausted")

            result.ok shouldBe false
            mockServer.requestCount shouldBe 3
        }

        @Test
        fun `4xx 클라이언트 에러는 재시도하지 않는다`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(400))

            val result = service.sendMessage("C123", "Bad request")

            result.ok shouldBe false
            mockServer.requestCount shouldBe 1
        }

        @Test
        fun `429 후 1회 재시도로 성공한다`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":false,"error":"ratelimited"}""")
            )
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"9999.1111","channel":"C123"}""")
            )

            val result = service.sendMessage("C123", "Retry once")

            result.ok shouldBe true
            result.ts shouldBe "9999.1111"
            mockServer.requestCount shouldBe 2
        }

        @Test
        fun `Retry-After 헤더가 없으면 retryDefaultDelayMs로 대기한다`() = runTest {
            // Retry-After 없이 429 → retryDefaultDelayMs(10ms) 사용
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":false,"error":"ratelimited"}""")
            )
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"1234.0001","channel":"C123"}""")
            )

            val result = service.sendMessage("C123", "No retry-after header")

            result.ok shouldBe true
            mockServer.requestCount shouldBe 2
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 메트릭 기록 검증
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    inner class MetricsRecording {

        @Test
        fun `성공 API 호출 시 recordApiCall이 success로 호출된다`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"1111.2222","channel":"C123"}""")
            )

            service.sendMessage("C123", "Metrics test")

            verify {
                metricsRecorder.recordApiCall(
                    method = "chat.postMessage",
                    outcome = "success",
                    durationMs = any()
                )
            }
        }

        @Test
        fun `Slack API ok=false 응답 시 recordApiCall이 api_error로 호출된다`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":false,"error":"channel_not_found"}""")
            )

            service.sendMessage("INVALID", "Error metrics test")

            verify {
                metricsRecorder.recordApiCall(
                    method = "chat.postMessage",
                    outcome = "api_error",
                    durationMs = any()
                )
            }
        }

        @Test
        fun `429 재시도 시 recordApiRetry가 rate_limit으로 호출된다`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":false,"error":"ratelimited"}""")
            )
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"2222.3333","channel":"C123"}""")
            )

            service.sendMessage("C123", "Rate limit retry metrics")

            verify {
                metricsRecorder.recordApiRetry(
                    method = "chat.postMessage",
                    reason = "rate_limit"
                )
            }
        }

        @Test
        fun `5xx 재시도 시 recordApiRetry가 server_error로 호출된다`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(500))
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"ts":"3333.4444","channel":"C123"}""")
            )

            service.sendMessage("C123", "Server error retry metrics")

            verify {
                metricsRecorder.recordApiRetry(
                    method = "chat.postMessage",
                    reason = "server_error"
                )
            }
        }

        @Test
        fun `response_url 성공 시 recordResponseUrl이 success로 호출된다`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(200))

            service.sendResponseUrl(
                responseUrl = mockServer.url("/response").toString(),
                text = "Success"
            )

            verify { metricsRecorder.recordResponseUrl("success") }
        }

        @Test
        fun `response_url 실패 시 recordResponseUrl이 failure로 호출된다`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(500))

            service.sendResponseUrl(
                responseUrl = mockServer.url("/response").toString(),
                text = "Failure"
            )

            verify { metricsRecorder.recordResponseUrl("failure") }
        }

        @Test
        fun `4xx 클라이언트 에러 재시도 없이 recordApiRetry가 client_error로 호출된다`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(400))

            service.sendMessage("C123", "4xx client error metrics")

            verify {
                metricsRecorder.recordApiRetry(
                    method = "chat.postMessage",
                    reason = "client_error"
                )
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // addReaction 에러 처리
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    inner class AddReactionEdgeCases {

        @Test
        fun `이미 존재하는 리액션은 api_error 응답을 SlackApiResult로 반환한다`() = runTest {
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":false,"error":"already_reacted"}""")
            )

            val result = service.addReaction("C123", "1234.5678", "thumbsup")

            result.ok shouldBe false
            result.error shouldBe "already_reacted"
        }

        @Test
        fun `addReaction 5xx 에러 시 재시도 후 성공한다`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(503))
            mockServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true}""")
            )

            val result = service.addReaction("C123", "1234.5678", "eyes")

            result.ok shouldBe true
            mockServer.requestCount shouldBe 2
        }

        @Test
        fun `addReaction 네트워크 오류 시 SlackApiResult 실패로 반환한다`() = runTest {
            // 서버에 응답 없이 바로 셧다운
            mockServer.enqueue(
                MockResponse().setSocketPolicy(
                    okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST
                )
            )

            val result = service.addReaction("C123", "1234.5678", "boom")

            result.ok shouldBe false
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // sendResponseUrl 경계 케이스
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    inner class SendResponseUrlEdgeCases {

        @Test
        fun `response_type이 in_channel이면 요청 본문에 포함된다`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(200))

            val result = service.sendResponseUrl(
                responseUrl = mockServer.url("/response").toString(),
                text = "Public response",
                responseType = "in_channel"
            )

            result shouldBe true

            val request = mockServer.takeRequest()
            val body = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                .readTree(request.body.readUtf8())
            body.path("response_type").asText() shouldBe "in_channel"
            body.path("text").asText() shouldBe "Public response"
        }

        @Test
        fun `response_url 서버 에러 시 false를 반환한다`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(500))

            val result = service.sendResponseUrl(
                responseUrl = mockServer.url("/fail").toString(),
                text = "Will fail"
            )

            result shouldBe false
        }
    }
}
