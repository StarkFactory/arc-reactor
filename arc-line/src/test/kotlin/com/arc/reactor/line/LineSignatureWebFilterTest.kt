package com.arc.reactor.line

import com.arc.reactor.line.security.LineSignatureVerifier
import com.arc.reactor.line.security.LineSignatureWebFilter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicReference

class LineSignatureWebFilterTest {

    private val channelSecret = "test-channel-secret-12345"
    private val verifier = LineSignatureVerifier(channelSecret)
    private val objectMapper = jacksonObjectMapper()
    private val filter = LineSignatureWebFilter(verifier, objectMapper)

    private fun createExchange(
        path: String,
        body: String,
        signature: String? = null
    ): MockServerWebExchange {
        val requestBuilder = MockServerHttpRequest.post(path)
            .contentType(MediaType.APPLICATION_JSON)

        if (signature != null) {
            requestBuilder.header("x-line-signature", signature)
        }

        val request = requestBuilder.body(body)
        return MockServerWebExchange.from(request)
    }

    private fun passThrough(): WebFilterChain = WebFilterChain { Mono.empty() }

    @Nested
    inner class NonLinePaths {

        @Test
        fun `passes through for non-line paths`() {
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
            val body = """{"events":[{"type":"message"}]}"""
            val signature = verifier.computeSignature(body)

            val exchange = createExchange("/api/line/webhook", body, signature)
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
            val body = """{"events":[]}"""

            val exchange = createExchange(
                "/api/line/webhook", body, "invalid-signature"
            )

            StepVerifier.create(filter.filter(exchange, passThrough()))
                .verifyComplete()

            exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `returns 403 for missing signature`() {
            val body = """{"events":[]}"""

            val exchange = createExchange("/api/line/webhook", body, null)

            StepVerifier.create(filter.filter(exchange, passThrough()))
                .verifyComplete()

            exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
        }
    }

    @Nested
    inner class BodyReplay {

        @Test
        fun `replays body for downstream handlers after verification`() {
            val body = """{"events":[{"type":"message","message":{"text":"hi"}}]}"""
            val signature = verifier.computeSignature(body)

            val exchange = createExchange("/api/line/webhook", body, signature)
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
