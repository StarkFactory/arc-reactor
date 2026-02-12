package com.arc.reactor.slack

import com.arc.reactor.slack.security.SlackSignatureVerifier
import com.arc.reactor.slack.security.SlackSignatureWebFilter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SlackSignatureWebFilterTest {

    private val signingSecret = "test-signing-secret-12345"
    private val verifier = SlackSignatureVerifier(signingSecret)
    private val objectMapper = jacksonObjectMapper()
    private val filter = SlackSignatureWebFilter(verifier, objectMapper)

    private fun computeSignature(timestamp: String, body: String): String {
        val baseString = "v0:$timestamp:$body"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(baseString.toByteArray(Charsets.UTF_8))
        return "v0=${hash.joinToString("") { "%02x".format(it) }}"
    }

    private fun currentTimestamp(): String = (System.currentTimeMillis() / 1000).toString()

    private fun createExchange(
        path: String,
        body: String,
        timestamp: String? = null,
        signature: String? = null
    ): MockServerWebExchange {
        val requestBuilder = MockServerHttpRequest.post(path)
            .contentType(MediaType.APPLICATION_JSON)

        if (timestamp != null) {
            requestBuilder.header("X-Slack-Request-Timestamp", timestamp)
        }
        if (signature != null) {
            requestBuilder.header("X-Slack-Signature", signature)
        }

        val request = requestBuilder.body(body)
        return MockServerWebExchange.from(request)
    }

    private fun passThrough(): WebFilterChain = WebFilterChain { Mono.empty() }

    @Nested
    inner class NonSlackPaths {

        @Test
        fun `passes through for non-slack paths`() {
            val exchange = createExchange("/api/chat", """{"message":"hello"}""")
            var chainCalled = false
            val chain = WebFilterChain {
                chainCalled = true
                Mono.empty()
            }

            StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete()

            chainCalled shouldBe true
        }

        @Test
        fun `passes through for actuator paths`() {
            val exchange = createExchange("/actuator/health", "")
            var chainCalled = false
            val chain = WebFilterChain {
                chainCalled = true
                Mono.empty()
            }

            StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete()

            chainCalled shouldBe true
        }
    }

    @Nested
    inner class SignatureVerification {

        @Test
        fun `allows request with valid signature`() {
            val body = """{"type":"event_callback","event":{"type":"app_mention"}}"""
            val timestamp = currentTimestamp()
            val signature = computeSignature(timestamp, body)

            val exchange = createExchange("/api/slack/events", body, timestamp, signature)
            var chainCalled = false
            val chain = WebFilterChain {
                chainCalled = true
                Mono.empty()
            }

            StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete()

            chainCalled shouldBe true
        }

        @Test
        fun `returns 403 for invalid signature`() {
            val body = """{"type":"event_callback"}"""
            val timestamp = currentTimestamp()

            val exchange = createExchange(
                "/api/slack/events", body, timestamp, "v0=invalidsignature"
            )

            StepVerifier.create(filter.filter(exchange, passThrough()))
                .verifyComplete()

            exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `returns 403 for missing signature`() {
            val body = """{"type":"event_callback"}"""
            val timestamp = currentTimestamp()

            val exchange = createExchange("/api/slack/events", body, timestamp, null)

            StepVerifier.create(filter.filter(exchange, passThrough()))
                .verifyComplete()

            exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `returns 403 for missing timestamp`() {
            val body = """{"type":"event_callback"}"""

            val exchange = createExchange("/api/slack/events", body, null, "v0=something")

            StepVerifier.create(filter.filter(exchange, passThrough()))
                .verifyComplete()

            exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `returns 403 for expired timestamp`() {
            val body = """{"type":"event_callback"}"""
            val expiredTimestamp = ((System.currentTimeMillis() / 1000) - 600).toString()
            val signature = computeSignature(expiredTimestamp, body)

            val exchange = createExchange("/api/slack/events", body, expiredTimestamp, signature)

            StepVerifier.create(filter.filter(exchange, passThrough()))
                .verifyComplete()

            exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
        }
    }

    @Nested
    inner class BodyReplay {

        @Test
        fun `replays body for downstream handlers after verification`() {
            val body = """{"type":"event_callback","event":{"text":"hello"}}"""
            val timestamp = currentTimestamp()
            val signature = computeSignature(timestamp, body)

            val exchange = createExchange("/api/slack/events", body, timestamp, signature)
            val capturedBody = AtomicReference<String>()

            val chain = WebFilterChain { ex ->
                ex.request.body
                    .map { dataBuffer ->
                        val bytes = ByteArray(dataBuffer.readableByteCount())
                        dataBuffer.read(bytes)
                        String(bytes, Charsets.UTF_8)
                    }
                    .doOnNext { capturedBody.set(it) }
                    .then()
            }

            StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete()

            capturedBody.get() shouldBe body
        }
    }
}
