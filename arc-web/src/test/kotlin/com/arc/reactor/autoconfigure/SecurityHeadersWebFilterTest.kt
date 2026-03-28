package com.arc.reactor.autoconfigure

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * SecurityHeadersWebFilter에 대한 테스트.
 *
 * 보안 헤더 웹 필터의 동작을 검증합니다.
 */
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
                "max-age=31536000; includeSubDomains; preload",
                exchange.response.headers.getFirst("Strict-Transport-Security")
            ) { "HSTS header should enforce HTTPS with preload directive" }
        }

        @Test
        fun `set Permissions-Policy해야 한다`() {
            val exchange = executeFilter()
            assertEquals(
                "geolocation=(), camera=(), microphone=(), payment=()",
                exchange.response.headers.getFirst("Permissions-Policy")
            ) { "Permissions-Policy should restrict browser features" }
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

        @Test
        fun `auth 경로에 Cache-Control no-store 설정해야 한다`() {
            val exchange = executeFilter("/api/auth/login")
            assertEquals(
                "no-store",
                exchange.response.headers.getFirst("Cache-Control")
            ) { "인증 경로는 캐시를 방지해야 한다" }
        }

        @Test
        fun `chat 경로에 Cache-Control no-store 설정해야 한다`() {
            val exchange = executeFilter("/api/chat/send")
            assertEquals(
                "no-store",
                exchange.response.headers.getFirst("Cache-Control")
            ) { "채팅 경로는 캐시를 방지해야 한다" }
        }

        @Test
        fun `일반 경로에 Cache-Control 미설정해야 한다`() {
            val exchange = executeFilter("/api/test")
            assertNull(
                exchange.response.headers.getFirst("Cache-Control")
            ) { "일반 경로에는 Cache-Control을 설정하지 않아야 한다" }
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
