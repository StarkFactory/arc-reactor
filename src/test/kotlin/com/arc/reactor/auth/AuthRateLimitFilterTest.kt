package com.arc.reactor.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.InetSocketAddress
import java.net.URI

class AuthRateLimitFilterTest {

    private lateinit var filter: AuthRateLimitFilter
    private lateinit var exchange: ServerWebExchange
    private lateinit var chain: WebFilterChain
    private lateinit var request: ServerHttpRequest
    private lateinit var response: ServerHttpResponse
    private lateinit var headers: HttpHeaders

    @BeforeEach
    fun setup() {
        filter = AuthRateLimitFilter(maxAttemptsPerMinute = 3)

        exchange = mockk(relaxed = true)
        chain = mockk()
        request = mockk()
        response = mockk(relaxed = true)
        headers = HttpHeaders()

        every { exchange.request } returns request
        every { exchange.response } returns response
        every { request.headers } returns headers
        every { request.remoteAddress } returns InetSocketAddress("127.0.0.1", 12345)
        every { chain.filter(exchange) } returns Mono.empty()
        every { response.bufferFactory() } returns DefaultDataBufferFactory()
    }

    @Nested
    inner class NonAuthPaths {

        @Test
        fun `should pass through for non-auth paths`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `should pass through for actuator paths`() {
            every { request.uri } returns URI.create("http://localhost/actuator/health")

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { chain.filter(exchange) }
        }
    }

    @Nested
    inner class RateLimiting {

        @Test
        fun `should allow requests within limit`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")

            for (i in 1..3) {
                val result = filter.filter(exchange, chain)
                result.block()
            }

            verify(exactly = 3) { chain.filter(exchange) }
        }

        @Test
        fun `should block requests exceeding limit with 429`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")

            // First 3 should pass
            for (i in 1..3) {
                filter.filter(exchange, chain).block()
            }

            // 4th should be blocked
            filter.filter(exchange, chain).block()

            verify(exactly = 3) { chain.filter(exchange) }
            verify(atLeast = 1) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }

        @Test
        fun `should also rate limit register endpoint`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/register")

            for (i in 1..4) {
                filter.filter(exchange, chain).block()
            }

            verify(exactly = 3) { chain.filter(exchange) }
            verify(atLeast = 1) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }
    }

    @Nested
    inner class IpExtraction {

        @Test
        fun `should use X-Forwarded-For header when present`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")

            // Exhaust limit for IP from X-Forwarded-For
            headers.set("X-Forwarded-For", "10.0.0.1, 192.168.1.1")
            for (i in 1..4) {
                filter.filter(exchange, chain).block()
            }

            // Request from different IP (no X-Forwarded-For) should still pass
            headers.remove("X-Forwarded-For")
            filter.filter(exchange, chain).block()

            // 3 from forwarded IP + 1 from direct IP = 4 chain.filter calls
            verify(exactly = 4) { chain.filter(exchange) }
        }

        @Test
        fun `should track separate limits per IP`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")

            // 3 requests from IP-A
            headers.set("X-Forwarded-For", "10.0.0.1")
            for (i in 1..3) {
                filter.filter(exchange, chain).block()
            }

            // 3 requests from IP-B should also pass (separate counter)
            headers.set("X-Forwarded-For", "10.0.0.2")
            for (i in 1..3) {
                filter.filter(exchange, chain).block()
            }

            verify(exactly = 6) { chain.filter(exchange) }
        }
    }

    @Nested
    inner class FilterOrder {

        @Test
        fun `should have high precedence order`() {
            assertEquals(
                Ordered.HIGHEST_PRECEDENCE + 1,
                filter.order,
                "Rate limit filter should run before auth filter"
            )
        }
    }
}
