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

    private fun executeFilter(path: String = "/api/test"): MockServerWebExchange {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get(path).build()
        )
        val chain = WebFilterChain { Mono.empty() }
        filter.filter(exchange, chain).block()
        return exchange
    }

    @Nested
    inner class Headers {

        @Test
        fun `set X-Content-Type-Options to nosniff해야 한다`() {
            val exchange = executeFilter()
            assertEquals(
                "nosniff",
                exchange.response.headers.getFirst("X-Content-Type-Options")
            ) { "X-Content-Type-Options should be nosniff" }
        }

        @Test
        fun `set X-Frame-Options to DENY해야 한다`() {
            val exchange = executeFilter()
            assertEquals(
                "DENY",
                exchange.response.headers.getFirst("X-Frame-Options")
            ) { "X-Frame-Options should be DENY" }
        }

        @Test
        fun `set Content-Security-Policy해야 한다`() {
            val exchange = executeFilter()
            assertEquals(
                "default-src 'self'",
                exchange.response.headers.getFirst("Content-Security-Policy")
            ) { "CSP should be set to default-src 'self'" }
        }

        @Test
        fun `set X-XSS-Protection to 0해야 한다`() {
            val exchange = executeFilter()
            assertEquals(
                "0",
                exchange.response.headers.getFirst("X-XSS-Protection")
            ) { "X-XSS-Protection should be 0 (modern best practice)" }
        }

        @Test
        fun `set Referrer-Policy해야 한다`() {
            val exchange = executeFilter()
            assertEquals(
                "strict-origin-when-cross-origin",
                exchange.response.headers.getFirst("Referrer-Policy")
            ) { "Referrer-Policy should be strict-origin-when-cross-origin" }
        }

        @Test
        fun `set Strict-Transport-Security해야 한다`() {
            val exchange = executeFilter()
            assertEquals(
                "max-age=31536000; includeSubDomains",
                exchange.response.headers.getFirst("Strict-Transport-Security")
            ) { "HSTS header should enforce HTTPS for one year including subdomains" }
        }

        @Test
        fun `swagger-ui paths에 대해 use relaxed CSP해야 한다`() {
            val exchange = executeFilter("/swagger-ui/index.html")
            val csp = exchange.response.headers.getFirst("Content-Security-Policy")
            assertTrue(csp!!.contains("'unsafe-inline'")) {
                "Swagger UI paths need unsafe-inline for inline styles/scripts, got: $csp"
            }
        }

        @Test
        fun `API paths에 대해 use strict CSP해야 한다`() {
            val exchange = executeFilter("/api/chat")
            assertEquals(
                "default-src 'self'",
                exchange.response.headers.getFirst("Content-Security-Policy")
            ) { "API paths should use strict CSP" }
        }
    }

    @Nested
    inner class Ordering {

        @Test
        fun `have highest precedence order해야 한다`() {
            assertEquals(
                Ordered.HIGHEST_PRECEDENCE,
                filter.order
            ) { "Filter should run before auth/rate-limit filters to cover early exits" }
        }
    }
}
