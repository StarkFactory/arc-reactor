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
import org.springframework.http.HttpMethod
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
    private var currentStatus: HttpStatus? = null

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
        every { request.method } returns HttpMethod.POST
        every { request.remoteAddress } returns InetSocketAddress("127.0.0.1", 12345)
        every { chain.filter(exchange) } returns Mono.empty()
        every { response.bufferFactory() } returns DefaultDataBufferFactory()
        every { response.statusCode } answers { currentStatus }
        currentStatus = HttpStatus.OK
    }

    @Nested
    inner class NonAuthPaths {

        @Test
        fun `non-auth paths에 대해 pass through해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `actuator paths에 대해 pass through해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/actuator/health")

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `not rate limit auth me endpoint해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/me")

            for (i in 1..4) {
                filter.filter(exchange, chain).block()
            }

            verify(exactly = 4) { chain.filter(exchange) }
            verify(exactly = 0) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }
    }

    @Nested
    inner class RateLimiting {

        @Test
        fun `allow successful requests without consuming the failure budget해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")

            for (i in 1..4) {
                val result = filter.filter(exchange, chain)
                result.block()
            }

            verify(exactly = 4) { chain.filter(exchange) }
            verify(exactly = 0) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }

        @Test
        fun `429 after repeated failures로 block requests exceeding limit해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")
            currentStatus = HttpStatus.UNAUTHORIZED

            // First 3은(는) pass해야 합니다
            for (i in 1..3) {
                filter.filter(exchange, chain).block()
            }

            // 4th은(는) be blocked해야 합니다
            filter.filter(exchange, chain).block()

            verify(exactly = 3) { chain.filter(exchange) }
            verify(atLeast = 1) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }

        @Test
        fun `a successful login 후 clear failed attempt history해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")
            currentStatus = HttpStatus.UNAUTHORIZED

            repeat(2) { filter.filter(exchange, chain).block() }

            currentStatus = HttpStatus.OK
            filter.filter(exchange, chain).block()

            currentStatus = HttpStatus.UNAUTHORIZED
            repeat(3) { filter.filter(exchange, chain).block() }

            verify(exactly = 6) { chain.filter(exchange) }
            verify(exactly = 0) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }

        @Test
        fun `also rate limit register endpoint on validation failures해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/register")
            currentStatus = HttpStatus.BAD_REQUEST

            for (i in 1..4) {
                filter.filter(exchange, chain).block()
            }

            verify(exactly = 3) { chain.filter(exchange) }
            verify(atLeast = 1) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }
    }

    @Nested
    inner class IpExtraction {

        private val trustedFilter = AuthRateLimitFilter(
            maxAttemptsPerMinute = 3,
            trustForwardedHeaders = true
        )

        @Test
        fun `trust is enabled일 때 use X-Forwarded-For해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")
            currentStatus = HttpStatus.UNAUTHORIZED

            headers.set("X-Forwarded-For", "10.0.0.1, 192.168.1.1")
            for (i in 1..4) {
                trustedFilter.filter(exchange, chain).block()
            }

            headers.remove("X-Forwarded-For")
            trustedFilter.filter(exchange, chain).block()

            verify(exactly = 4) { chain.filter(exchange) }
        }

        @Test
        fun `trust is enabled일 때 track separate limits per IP해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")
            currentStatus = HttpStatus.UNAUTHORIZED

            headers.set("X-Forwarded-For", "10.0.0.1")
            for (i in 1..3) {
                trustedFilter.filter(exchange, chain).block()
            }

            headers.set("X-Forwarded-For", "10.0.0.2")
            for (i in 1..3) {
                trustedFilter.filter(exchange, chain).block()
            }

            verify(exactly = 6) { chain.filter(exchange) }
        }

        @Test
        fun `trust is disabled일 때 ignore X-Forwarded-For해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")
            currentStatus = HttpStatus.UNAUTHORIZED

            headers.set("X-Forwarded-For", "10.0.0.1")
            for (i in 1..3) {
                filter.filter(exchange, chain).block()
            }

            headers.set("X-Forwarded-For", "10.0.0.2")
            filter.filter(exchange, chain).block()

            // All requests은(는) be tracked under 127.0.0.1 (direct IP)해야 합니다
            // 3 + 1 blocked = 3 chain.filter calls
            verify(exactly = 3) { chain.filter(exchange) }
        }
    }

    @Nested
    inner class FilterOrder {

        @Test
        fun `have high precedence order해야 한다`() {
            assertEquals(
                Ordered.HIGHEST_PRECEDENCE + 1,
                filter.order,
                "Rate limit filter should run before auth filter"
            )
        }
    }
}
