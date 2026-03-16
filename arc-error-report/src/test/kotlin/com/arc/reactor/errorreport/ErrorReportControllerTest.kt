package com.arc.reactor.errorreport

import com.arc.reactor.errorreport.config.ErrorReportProperties
import com.arc.reactor.errorreport.controller.ErrorReportController
import com.arc.reactor.errorreport.handler.ErrorReportHandler
import com.arc.reactor.errorreport.model.ErrorReportRequest
import com.arc.reactor.errorreport.model.ErrorReportResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.delay
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * ErrorReportController에 대한 테스트.
 *
 * 에러 리포트 REST API의 동작을 검증합니다.
 */
class ErrorReportControllerTest {

    private val handler = mockk<ErrorReportHandler>(relaxed = true)

    private fun createClient(
        apiKey: String = "",
        requestTimeoutMs: Long = 120_000,
        maxConcurrentRequests: Int = 3,
        maxStackTraceLength: Int = 30_000,
        handlerOverride: ErrorReportHandler = handler
    ): WebTestClient {
        val properties = ErrorReportProperties(
            enabled = true,
            apiKey = apiKey,
            requestTimeoutMs = requestTimeoutMs,
            maxConcurrentRequests = maxConcurrentRequests,
            maxStackTraceLength = maxStackTraceLength
        )
        val controller = ErrorReportController(handlerOverride, properties)
        return WebTestClient.bindToController(controller).build()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private val validBody = mapOf(
        "stackTrace" to "java.lang.NullPointerException\n\tat com.example.Main.run(Main.kt:10)",
        "serviceName" to "my-service",
        "repoSlug" to "my-org/my-service",
        "slackChannel" to "#error-alerts",
        "environment" to "production"
    )

    @Nested
    inner class SuccessfulRequest {

        @Test
        fun `200 with accepted response를 반환한다`() {
            val client = createClient()
            val result = client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isOk
                .expectBody(ErrorReportResponse::class.java)
                .returnResult()
                .responseBody!!

            result.accepted shouldBe true
            result.requestId.shouldNotBeBlank()
        }

        @Test
        fun `request with optional fields omitted를 수락한다`() {
            val client = createClient()
            val body = mapOf(
                "stackTrace" to "Error",
                "serviceName" to "svc",
                "repoSlug" to "org/repo",
                "slackChannel" to "#ch"
            )
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk
        }
    }

    @Nested
    inner class Validation {

        @Test
        fun `stackTrace is blank일 때 400를 반환한다`() {
            val client = createClient()
            val body = validBody + ("stackTrace" to "")
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `serviceName is missing일 때 400를 반환한다`() {
            val client = createClient()
            val body = validBody.filterKeys { it != "serviceName" }
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `repoSlug is missing일 때 400를 반환한다`() {
            val client = createClient()
            val body = validBody.filterKeys { it != "repoSlug" }
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `slackChannel is missing일 때 400를 반환한다`() {
            val client = createClient()
            val body = validBody.filterKeys { it != "slackChannel" }
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
        }
    }

    @Nested
    inner class ApiKeyAuthentication {

        @Test
        fun `api key is required but missing일 때 401를 반환한다`() {
            val client = createClient(apiKey = "secret-key")
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isUnauthorized
                .expectBody()
                .jsonPath("$.error").isEqualTo("Invalid or missing API key")
                .jsonPath("$.timestamp").exists()
        }

        @Test
        fun `api key is incorrect일 때 401를 반환한다`() {
            val client = createClient(apiKey = "secret-key")
            client.post().uri("/api/error-report")
                .header("X-API-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isUnauthorized
        }

        @Test
        fun `api key matches일 때 200를 반환한다`() {
            val client = createClient(apiKey = "secret-key")
            client.post().uri("/api/error-report")
                .header("X-API-Key", "secret-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isOk
        }

        @Test
        fun `skips auth when api key은(는) not configured이다`() {
            val client = createClient(apiKey = "")
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isOk
        }
    }

    @Nested
    inner class TimeoutBehavior {

        @Test
        fun `processing exceeds timeout일 때 cancels handler`() {
            val started = AtomicBoolean(false)
            val cancelled = AtomicBoolean(false)
            val slowHandler = object : ErrorReportHandler {
                override suspend fun handle(requestId: String, request: ErrorReportRequest) {
                    started.set(true)
                    try {
                        delay(500)
                    } catch (e: CancellationException) {
                        cancelled.set(true)
                        throw e
                    }
                }
            }
            val client = createClient(
                requestTimeoutMs = 50,
                maxConcurrentRequests = 1,
                handlerOverride = slowHandler
            )

            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isOk

            waitUntil(500) { started.get() } shouldBe true
            waitUntil(1500) { cancelled.get() } shouldBe true
        }
    }

    @Nested
    inner class ConfigurationHardening {

        @Test
        fun `accepts request when max concurrent requests은(는) zero이다`() {
            val client = createClient(maxConcurrentRequests = 0)
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isOk
        }

        @Test
        fun `accepts request when max stack trace length은(는) negative이다`() {
            val client = createClient(maxStackTraceLength = -1)
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isOk
        }
    }
}
