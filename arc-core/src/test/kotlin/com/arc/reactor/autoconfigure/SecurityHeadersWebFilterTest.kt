package com.arc.reactor.autoconfigure

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class SecurityHeadersWebFilterTest {

    private val filter = SecurityHeadersWebFilter()

    private fun executeFilter(): MockServerWebExchange {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/test").build()
        )
        val chain = WebFilterChain { Mono.empty() }
        filter.filter(exchange, chain).block()
        return exchange
    }

    @Nested
    inner class Headers {

        @Test
        fun `should set X-Content-Type-Options to nosniff`() {
            val exchange = executeFilter()
            assertEquals(
                "nosniff",
                exchange.response.headers.getFirst("X-Content-Type-Options")
            ) { "X-Content-Type-Options should be nosniff" }
        }

        @Test
        fun `should set X-Frame-Options to DENY`() {
            val exchange = executeFilter()
            assertEquals(
                "DENY",
                exchange.response.headers.getFirst("X-Frame-Options")
            ) { "X-Frame-Options should be DENY" }
        }

        @Test
        fun `should set Content-Security-Policy`() {
            val exchange = executeFilter()
            assertEquals(
                "default-src 'self'",
                exchange.response.headers.getFirst("Content-Security-Policy")
            ) { "CSP should be set to default-src 'self'" }
        }

        @Test
        fun `should set X-XSS-Protection to 0`() {
            val exchange = executeFilter()
            assertEquals(
                "0",
                exchange.response.headers.getFirst("X-XSS-Protection")
            ) { "X-XSS-Protection should be 0 (modern best practice)" }
        }

        @Test
        fun `should set Referrer-Policy`() {
            val exchange = executeFilter()
            assertEquals(
                "strict-origin-when-cross-origin",
                exchange.response.headers.getFirst("Referrer-Policy")
            ) { "Referrer-Policy should be strict-origin-when-cross-origin" }
        }
    }

    @Nested
    inner class Ordering {

        @Test
        fun `should have order just after HIGHEST_PRECEDENCE`() {
            assertEquals(
                Ordered.HIGHEST_PRECEDENCE + 1,
                filter.order
            ) { "Filter should run right after HIGHEST_PRECEDENCE" }
        }
    }
}
