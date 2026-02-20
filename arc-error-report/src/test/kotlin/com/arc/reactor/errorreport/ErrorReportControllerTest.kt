package com.arc.reactor.errorreport

import com.arc.reactor.errorreport.config.ErrorReportProperties
import com.arc.reactor.errorreport.controller.ErrorReportController
import com.arc.reactor.errorreport.handler.ErrorReportHandler
import com.arc.reactor.errorreport.model.ErrorReportResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class ErrorReportControllerTest {

    private val handler = mockk<ErrorReportHandler>(relaxed = true)

    private fun createClient(apiKey: String = ""): WebTestClient {
        val properties = ErrorReportProperties(enabled = true, apiKey = apiKey)
        val controller = ErrorReportController(handler, properties)
        return WebTestClient.bindToController(controller).build()
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
        fun `returns 200 with accepted response`() {
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
        fun `accepts request with optional fields omitted`() {
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
        fun `returns 400 when stackTrace is blank`() {
            val client = createClient()
            val body = validBody + ("stackTrace" to "")
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `returns 400 when serviceName is missing`() {
            val client = createClient()
            val body = validBody.filterKeys { it != "serviceName" }
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `returns 400 when repoSlug is missing`() {
            val client = createClient()
            val body = validBody.filterKeys { it != "repoSlug" }
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `returns 400 when slackChannel is missing`() {
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
        fun `returns 401 when api key is required but missing`() {
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
        fun `returns 401 when api key is incorrect`() {
            val client = createClient(apiKey = "secret-key")
            client.post().uri("/api/error-report")
                .header("X-API-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isUnauthorized
        }

        @Test
        fun `returns 200 when api key matches`() {
            val client = createClient(apiKey = "secret-key")
            client.post().uri("/api/error-report")
                .header("X-API-Key", "secret-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isOk
        }

        @Test
        fun `skips auth when api key is not configured`() {
            val client = createClient(apiKey = "")
            client.post().uri("/api/error-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody)
                .exchange()
                .expectStatus().isOk
        }
    }
}
