package com.arc.reactor.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URI

class JwtAuthWebFilterTest {

    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var authProperties: AuthProperties
    private lateinit var filter: JwtAuthWebFilter
    private lateinit var exchange: ServerWebExchange
    private lateinit var chain: WebFilterChain
    private lateinit var request: ServerHttpRequest
    private lateinit var response: ServerHttpResponse
    private lateinit var headers: HttpHeaders
    private lateinit var attributes: MutableMap<String, Any>

    @BeforeEach
    fun setup() {
        jwtTokenProvider = mockk()
        authProperties = AuthProperties(
            enabled = true,
            jwtSecret = "arc-reactor-test-jwt-secret-key-at-least-32-chars-long",
            jwtExpirationMs = 86_400_000,
            publicPaths = listOf("/api/auth/login", "/api/auth/register")
        )
        filter = JwtAuthWebFilter(jwtTokenProvider, authProperties)

        exchange = mockk(relaxed = true)
        chain = mockk()
        request = mockk()
        response = mockk(relaxed = true)
        headers = HttpHeaders()
        attributes = mutableMapOf()

        every { exchange.request } returns request
        every { exchange.response } returns response
        every { exchange.attributes } returns attributes
        every { request.headers } returns headers
        every { chain.filter(exchange) } returns Mono.empty()
        every { response.setComplete() } returns Mono.empty()
    }

    @Nested
    inner class PublicPaths {

        @Test
        fun `should pass through for public login path`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `should pass through for public register path`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/register")

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { chain.filter(exchange) }
        }
    }

    @Nested
    inner class AuthenticationRequired {

        @Test
        fun `should return 401 when Authorization header is missing`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            // headers is empty - no Authorization header

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `should return 401 for malformed Authorization header without Bearer prefix`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Basic some-token")

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `should return 401 for invalid token`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
            every { jwtTokenProvider.validateToken("invalid-token") } returns null

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `should return 401 for expired token`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer expired-token")
            every { jwtTokenProvider.validateToken("expired-token") } returns null

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `should set userId attribute and pass through for valid token`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
            every { jwtTokenProvider.validateToken("valid-token") } returns "user-42"
            every { jwtTokenProvider.extractRole("valid-token") } returns UserRole.USER

            val result = filter.filter(exchange, chain)
            result.block()

            assertEquals("user-42", attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE]) {
                "userId should be stored in exchange attributes"
            }
            assertEquals(UserRole.USER, attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE]) {
                "userRole should be stored in exchange attributes"
            }
            verify(exactly = 1) { chain.filter(exchange) }
        }
    }
}
