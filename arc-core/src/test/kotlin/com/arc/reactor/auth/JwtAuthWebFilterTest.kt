package com.arc.reactor.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URI

/**
 * JwtAuthWebFilter에 대한 테스트.
 *
 * JWT 인증 웹 필터의 동작을 검증합니다.
 */
class JwtAuthWebFilterTest {

    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var authProperties: AuthProperties
    private lateinit var authProvider: AuthProvider
    private lateinit var tokenRevocationStore: TokenRevocationStore
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
        authProvider = mockk()
        tokenRevocationStore = mockk()
        authProperties = AuthProperties(
            jwtSecret = "arc-reactor-test-jwt-secret-key-at-least-32-chars-long",
            jwtExpirationMs = 86_400_000,
            publicPaths = listOf("/api/auth/login", "/api/auth/register")
        )
        filter = JwtAuthWebFilter(jwtTokenProvider, authProperties, authProvider, tokenRevocationStore)

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
        every { jwtTokenProvider.extractTenantId(any()) } returns null
        every { jwtTokenProvider.extractEmail(any()) } returns null
        every { jwtTokenProvider.extractAccountId(any()) } returns null
        every { jwtTokenProvider.extractTokenId(any()) } returns "default-jti"
        every { authProvider.getUserById(any()) } returns null
        every { tokenRevocationStore.isRevoked(any()) } returns false
    }

    @Nested
    inner class PublicPaths {

        @Test
        fun `public login path에 대해 pass through해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `public register path에 대해 pass through해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/register")

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { chain.filter(exchange) }
        }
    }

    @Nested
    inner class AuthenticationRequired {

        @Test
        fun `Authorization header is missing일 때 return 401해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            // headers is empty - no Authorization header

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `malformed Authorization header without Bearer prefix에 대해 return 401해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Basic some-token")

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `invalid token에 대해 return 401해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
            every { jwtTokenProvider.validateToken("invalid-token") } returns null

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `expired token에 대해 return 401해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer expired-token")
            every { jwtTokenProvider.validateToken("expired-token") } returns null

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `valid token에 대해 set userId attribute and pass through해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
            every { jwtTokenProvider.validateToken("valid-token") } returns "user-42"
            every { jwtTokenProvider.extractRole("valid-token") } returns UserRole.USER
            every { jwtTokenProvider.extractEmail("valid-token") } returns "token-user@example.com"
            every { jwtTokenProvider.extractAccountId("valid-token") } returns "acct-001"
            every { authProvider.getUserById("user-42") } returns User(
                id = "user-42",
                email = "user@example.com",
                name = "User",
                passwordHash = "hashed",
                role = UserRole.USER
            )

            val result = filter.filter(exchange, chain)
            result.block()

            assertEquals("user-42", attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE]) {
                "userId should be stored in exchange attributes"
            }
            assertEquals(UserRole.USER, attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE]) {
                "userRole should be stored in exchange attributes"
            }
            assertEquals("default", attributes[JwtAuthWebFilter.RESOLVED_TENANT_ID_ATTRIBUTE]) {
                "resolvedTenantId should default to auth default tenant when token tenant is absent"
            }
            assertEquals("token-user@example.com", attributes[JwtAuthWebFilter.USER_EMAIL_ATTRIBUTE]) {
                "userEmail should be copied from token claim"
            }
            assertEquals("acct-001", attributes[JwtAuthWebFilter.USER_ACCOUNT_ID_ATTRIBUTE]) {
                "userAccountId should be copied from token claim"
            }
            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `accountId claim is missing일 때 fallback to userId해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer valid-token-no-account-id")
            every { jwtTokenProvider.validateToken("valid-token-no-account-id") } returns "user-42"
            every { jwtTokenProvider.extractRole("valid-token-no-account-id") } returns UserRole.USER
            every { jwtTokenProvider.extractEmail("valid-token-no-account-id") } returns "token-user@example.com"
            every { jwtTokenProvider.extractAccountId("valid-token-no-account-id") } returns null
            every { jwtTokenProvider.extractTenantId("valid-token-no-account-id") } returns "tenant-acme"
            every { authProvider.getUserById("user-42") } returns User(
                id = "user-42",
                email = "user@example.com",
                name = "User",
                passwordHash = "hashed",
                role = UserRole.USER
            )

            val result = filter.filter(exchange, chain)
            result.block()

            assertEquals("user-42", attributes[JwtAuthWebFilter.USER_ACCOUNT_ID_ATTRIBUTE]) {
                "userAccountId should fallback to userId when claim is missing"
            }
            assertEquals("tenant-acme", attributes[JwtAuthWebFilter.RESOLVED_TENANT_ID_ATTRIBUTE]) {
                "resolvedTenantId should still be copied from token tenant claim"
            }
            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `token email claim is absent일 때 fallback to auth user email해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer valid-token-no-email")
            every { jwtTokenProvider.validateToken("valid-token-no-email") } returns "user-42"
            every { jwtTokenProvider.extractRole("valid-token-no-email") } returns UserRole.USER
            every { jwtTokenProvider.extractEmail("valid-token-no-email") } returns null
            every { authProvider.getUserById("user-42") } returns User(
                id = "user-42",
                email = "from-db@example.com",
                name = "User",
                passwordHash = "hashed",
                role = UserRole.USER
            )

            val result = filter.filter(exchange, chain)
            result.block()

            assertEquals("from-db@example.com", attributes[JwtAuthWebFilter.USER_EMAIL_ATTRIBUTE]) {
                "userEmail should fallback to auth provider email when token email claim is missing"
            }
            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `present일 때 set resolvedTenantId from token claim해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
            every { jwtTokenProvider.validateToken("valid-token") } returns "user-42"
            every { jwtTokenProvider.extractRole("valid-token") } returns UserRole.USER
            every { jwtTokenProvider.extractTenantId("valid-token") } returns "tenant-acme"
            every { authProvider.getUserById("user-42") } returns User(
                id = "user-42",
                email = "user@example.com",
                name = "User",
                passwordHash = "hashed",
                role = UserRole.USER
            )

            val result = filter.filter(exchange, chain)
            result.block()

            assertEquals("tenant-acme", attributes[JwtAuthWebFilter.RESOLVED_TENANT_ID_ATTRIBUTE]) {
                "resolvedTenantId should be copied from token tenant claim"
            }
            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `use current role from auth provider over token claim해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
            every { jwtTokenProvider.validateToken("valid-token") } returns "user-42"
            every { jwtTokenProvider.extractRole("valid-token") } returns UserRole.ADMIN
            every { authProvider.getUserById("user-42") } returns User(
                id = "user-42",
                email = "user@example.com",
                name = "User",
                passwordHash = "hashed",
                role = UserRole.USER
            )

            val result = filter.filter(exchange, chain)
            result.block()

            assertEquals(UserRole.USER, attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE]) {
                "role should follow current persisted user role, not stale token role"
            }
            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `token user no longer exists일 때 return 401해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
            every { jwtTokenProvider.validateToken("valid-token") } returns "user-42"
            every { jwtTokenProvider.extractRole("valid-token") } returns UserRole.USER
            every { authProvider.getUserById("user-42") } returns null

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `token is revoked일 때 return 401해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer revoked-token")
            every { jwtTokenProvider.validateToken("revoked-token") } returns "user-42"
            every { jwtTokenProvider.extractTokenId("revoked-token") } returns "jti-42"
            every { tokenRevocationStore.isRevoked("jti-42") } returns true

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `blank userId(sub claim)일 때 return 401해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer blank-sub-token")
            every { jwtTokenProvider.validateToken("blank-sub-token") } returns ""

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `jti 없는 토큰은 revocation store 활성 시 return 401해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer no-jti-token")
            every { jwtTokenProvider.validateToken("no-jti-token") } returns "user-42"
            every { jwtTokenProvider.extractTokenId("no-jti-token") } returns null

            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }

        @Test
        fun `R323 revocation store 예외는 fail-closed로 401 반환해야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/chat")
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer store-error-token")
            every { jwtTokenProvider.validateToken("store-error-token") } returns "user-42"
            every { jwtTokenProvider.extractTokenId("store-error-token") } returns "jti-42"
            every { tokenRevocationStore.isRevoked("jti-42") } throws RuntimeException("DB down")

            // revocation store 예외 발생 시 fail-closed로 401을 반환해야 한다 (500 전파 금지)
            val result = filter.filter(exchange, chain)
            result.block()

            verify(exactly = 1) { response.statusCode = HttpStatus.UNAUTHORIZED }
            verify(exactly = 0) { chain.filter(exchange) }
        }
    }

    @Nested
    inner class FilterOrder {

        @Test
        fun `security headers and rate-limit filters 후 run해야 한다`() {
            assertEquals(
                Ordered.HIGHEST_PRECEDENCE + 2,
                filter.order,
                "JWT auth filter should run after security headers and auth rate-limit filters"
            )
        }
    }
}
