package com.arc.reactor.controller

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

/**
 * McpAdminProxySupport에 대한 보안 테스트.
 *
 * sanitizeHeaderValue의 CRLF 인젝션 방어, 길이 제한,
 * resolveRequestId의 헤더 우선순위를 검증한다.
 */
class McpAdminProxySupportTest {

    @Nested
    inner class SanitizeHeaderValue {

        @Test
        fun `CRLF 문자를 제거하여 헤더 분리를 방지한다`() {
            val request = MockServerHttpRequest.get("/")
                .header("X-Request-Id", "legit\r\ninjected")
                .build()
            val exchange = MockServerWebExchange.from(request)

            val requestId = McpAdminProxySupport.resolveRequestId(exchange)

            // CRLF 제거 후 "legitinjected"가 되어야 한다 (헤더 분리 방지)
            requestId shouldNotContain "\r"
            requestId shouldNotContain "\n"
        }

        @Test
        fun `개행만 있는 경우에도 제거한다`() {
            val request = MockServerHttpRequest.get("/")
                .header("X-Request-Id", "id\ninjected")
                .build()
            val exchange = MockServerWebExchange.from(request)

            val requestId = McpAdminProxySupport.resolveRequestId(exchange)

            requestId shouldNotContain "\n"
        }

        @Test
        fun `128자 길이 제한을 적용한다`() {
            val longValue = "a".repeat(200)
            val request = MockServerHttpRequest.get("/")
                .header("X-Request-Id", longValue)
                .build()
            val exchange = MockServerWebExchange.from(request)

            val requestId = McpAdminProxySupport.resolveRequestId(exchange)

            requestId.length shouldBe 128
        }

        @Test
        fun `빈 X-Request-Id는 UUID 폴백을 사용한다`() {
            val request = MockServerHttpRequest.get("/")
                .header("X-Request-Id", "")
                .build()
            val exchange = MockServerWebExchange.from(request)

            val requestId = McpAdminProxySupport.resolveRequestId(exchange)

            requestId.startsWith("arc-") shouldBe true
        }

        @Test
        fun `헤더가 없으면 UUID 폴백을 사용한다`() {
            val request = MockServerHttpRequest.get("/").build()
            val exchange = MockServerWebExchange.from(request)

            val requestId = McpAdminProxySupport.resolveRequestId(exchange)

            requestId.startsWith("arc-") shouldBe true
        }
    }

    @Nested
    inner class ResolveRequestIdPriority {

        @Test
        fun `X-Request-Id가 X-Correlation-Id보다 우선한다`() {
            val request = MockServerHttpRequest.get("/")
                .header("X-Request-Id", "req-123")
                .header("X-Correlation-Id", "corr-456")
                .build()
            val exchange = MockServerWebExchange.from(request)

            McpAdminProxySupport.resolveRequestId(exchange) shouldBe "req-123"
        }

        @Test
        fun `X-Request-Id가 없으면 X-Correlation-Id를 사용한다`() {
            val request = MockServerHttpRequest.get("/")
                .header("X-Correlation-Id", "corr-456")
                .build()
            val exchange = MockServerWebExchange.from(request)

            McpAdminProxySupport.resolveRequestId(exchange) shouldBe "corr-456"
        }
    }
}
