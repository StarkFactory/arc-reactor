package com.arc.reactor.autoconfigure

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class RequestCorrelationFilterTest {

    private val filter = RequestCorrelationFilter()

    @Nested
    inner class WhenNoRequestHeader {

        @Test
        fun `should generate UUID and set response header`() {
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build()
            )
            val chain = WebFilterChain { Mono.empty() }
            filter.filter(exchange, chain).block()

            val header = exchange.response.headers.getFirst("X-Request-ID")
            assertNotNull(header) { "Response should contain X-Request-ID header" }
            assertFalse(header!!.isBlank()) { "X-Request-ID should not be blank" }
        }

        @Test
        fun `should generate different IDs for separate requests`() {
            val chain = WebFilterChain { Mono.empty() }

            val exchange1 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build()
            )
            filter.filter(exchange1, chain).block()

            val exchange2 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build()
            )
            filter.filter(exchange2, chain).block()

            val id1 = exchange1.response.headers.getFirst("X-Request-ID")
            val id2 = exchange2.response.headers.getFirst("X-Request-ID")
            assertNotEquals(id1, id2) { "Each request should get a unique ID" }
        }
    }

    @Nested
    inner class WhenRequestHeaderPresent {

        @Test
        fun `should reuse client-provided X-Request-ID`() {
            val clientId = "client-correlation-123"
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                    .header("X-Request-ID", clientId)
                    .build()
            )
            val chain = WebFilterChain { Mono.empty() }
            filter.filter(exchange, chain).block()

            assertEquals(
                clientId,
                exchange.response.headers.getFirst("X-Request-ID")
            ) { "Response should echo the client-provided X-Request-ID" }
        }

        @Test
        fun `should generate new ID when header is blank`() {
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                    .header("X-Request-ID", "   ")
                    .build()
            )
            val chain = WebFilterChain { Mono.empty() }
            filter.filter(exchange, chain).block()

            val header = exchange.response.headers.getFirst("X-Request-ID")
            assertNotNull(header) { "Response should contain X-Request-ID" }
            assertNotEquals(
                "   ", header
            ) { "Blank client header should be replaced with a generated ID" }
        }
    }

    @Nested
    inner class ReactorContextPropagation {

        @Test
        fun `should write requestId into Reactor context`() {
            val clientId = "ctx-test-456"
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                    .header("X-Request-ID", clientId)
                    .build()
            )
            val chain = WebFilterChain {
                Mono.deferContextual { ctx ->
                    val propagated = ctx.getOrDefault(
                        RequestCorrelationFilter.CONTEXT_KEY, ""
                    )
                    assertEquals(
                        clientId, propagated
                    ) { "Reactor context should contain the requestId" }
                    Mono.empty()
                }
            }

            StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete()
        }
    }

    @Nested
    inner class Ordering {

        @Test
        fun `should run after security headers and before API version filter`() {
            assertEquals(
                Ordered.HIGHEST_PRECEDENCE + 1,
                filter.order
            ) { "Filter order should be HIGHEST_PRECEDENCE + 1" }
        }
    }
}
