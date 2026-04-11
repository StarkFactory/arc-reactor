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

/**
 * AuthRateLimitFilter에 대한 테스트.
 *
 * 인증 속도 제한 필터의 동작을 검증합니다.
 */
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

            // 처음 3개는 통과해야 합니다
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

        @Test
        fun `R325 null status 응답은 failure counter를 리셋하지 않아야 한다`() {
            // R325: 기존 구현은 null status를 성공으로 간주하여 cache.invalidate 호출 →
            // 공격자가 null status 응답을 유도할 수 있다면 brute-force 한도 우회 가능.
            // 2번의 실패 후 null status 응답이 와도 카운터가 남아있어야 하며, 추가 2번의
            // 실패로 3번째 실패 + 1번의 차단이 정확히 발생해야 한다.
            every { request.uri } returns URI.create("http://localhost/api/auth/login")

            // 1-2번째: 401 실패 → 카운터 2
            currentStatus = HttpStatus.UNAUTHORIZED
            repeat(2) { filter.filter(exchange, chain).block() }

            // 3번째: null status (중간에 응답 commit 전 fire) → 카운터 유지
            currentStatus = null
            filter.filter(exchange, chain).block()

            // 4번째: 401 → 카운터 3 (maxAttemptsPerMinute=3에 도달)
            currentStatus = HttpStatus.UNAUTHORIZED
            filter.filter(exchange, chain).block()

            // 5번째: isBlocked=true → 429 차단되어야 한다 (카운터가 리셋되지 않았음)
            filter.filter(exchange, chain).block()

            verify(exactly = 4) { chain.filter(exchange) }
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

            // 모든 요청은 127.0.0.1 (직접 IP)로 추적되어야 합니다
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
