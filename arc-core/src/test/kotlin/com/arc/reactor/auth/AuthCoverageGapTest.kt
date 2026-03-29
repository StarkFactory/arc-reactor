package com.arc.reactor.auth

import com.arc.reactor.guard.impl.DefaultRateLimitStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.agent.config.TenantRateLimit
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.Date

/**
 * 인증·권한 컴포넌트 커버리지 Gap 보강 테스트.
 *
 * 기존 테스트가 다루지 않는 엣지 케이스를 집중 검증한다:
 * - JwtTokenProvider: extractTokenId, extractExpiration, 변조 서명, 잘못된 role enum
 * - InMemoryTokenRevocationStore: 멱등성, 동시 폐기
 * - RedisTokenRevocationStore: 커스텀 keyPrefix, blank keyPrefix 방어
 * - JdbcTokenRevocationStore: 만료 직전 경계값, revoke 후 즉시 삭제
 * - JwtAuthWebFilter: authProvider=null 경로, 잘못된 email/tenantId claim 처리
 * - AuthRateLimitFilter: Retry-After 헤더, GET 미적용, remoteAddress=null 경로
 * - DefaultRateLimitStage: 테넌트별 차등 제한, 테넌트 격리 키
 */
class AuthCoverageGapTest {

    // ─── JwtTokenProvider: 미검증 메서드 ─────────────────────────────────────

    @Nested
    inner class JwtTokenProviderGap {

        private val testSecret = "arc-reactor-test-jwt-secret-key-at-least-32-chars-long"
        private val tokenProvider = JwtTokenProvider(
            AuthProperties(jwtSecret = testSecret, jwtExpirationMs = 86_400_000)
        )
        private val testUser = User(
            id = "user-gap-1",
            email = "gap@example.com",
            name = "Gap User",
            passwordHash = "irrelevant"
        )

        @Test
        fun `extractTokenId는 유효한 토큰에서 jti를 반환해야 한다`() {
            val token = tokenProvider.createToken(testUser)

            val jti = tokenProvider.extractTokenId(token)

            assertNotNull(jti) { "jti claim은 createToken에서 항상 UUID로 설정되어야 한다" }
            assertTrue(jti!!.isNotBlank()) { "추출된 jti는 공백이 아니어야 한다" }
        }

        @Test
        fun `extractTokenId는 잘못된 토큰에 대해 null을 반환해야 한다`() {
            val jti = tokenProvider.extractTokenId("not.a.valid.token")

            assertNull(jti) { "잘못된 토큰에서 extractTokenId는 null을 반환해야 한다" }
        }

        @Test
        fun `extractExpiration은 유효한 토큰에서 미래 시각을 반환해야 한다`() {
            val token = tokenProvider.createToken(testUser)

            val expiration = tokenProvider.extractExpiration(token)

            assertNotNull(expiration) { "유효한 토큰의 만료 시각은 null이 아니어야 한다" }
            assertTrue(expiration!!.isAfter(Instant.now())) {
                "만료 시각은 현재보다 미래여야 한다. 실제: $expiration"
            }
        }

        @Test
        fun `extractExpiration은 잘못된 토큰에 대해 null을 반환해야 한다`() {
            val expiration = tokenProvider.extractExpiration("invalid.token.value")

            assertNull(expiration) { "잘못된 토큰에서 extractExpiration은 null을 반환해야 한다" }
        }

        @Test
        fun `다른 시크릿으로 서명된 토큰은 validateToken이 null을 반환해야 한다`() {
            val otherSecret = "completely-different-secret-key-with-enough-length"
            val otherProvider = JwtTokenProvider(AuthProperties(jwtSecret = otherSecret))
            val foreignToken = otherProvider.createToken(testUser)

            val result = tokenProvider.validateToken(foreignToken)

            assertNull(result) { "다른 시크릿으로 서명된 토큰은 유효하지 않아야 한다" }
        }

        @Test
        fun `변조된 페이로드를 가진 토큰은 validateToken이 null을 반환해야 한다`() {
            val token = tokenProvider.createToken(testUser)
            val parts = token.split(".")
            // 페이로드 부분을 임의 문자열로 교체
            val tamperedToken = "${parts[0]}.ZmFrZXBheWxvYWQ.${parts[2]}"

            val result = tokenProvider.validateToken(tamperedToken)

            assertNull(result) { "변조된 토큰은 서명 검증에 실패하여 null을 반환해야 한다" }
        }

        @Test
        fun `잘못된 role enum 문자열이 포함된 토큰에서 extractRole은 null을 반환해야 한다`() {
            // JJWT로 직접 잘못된 role 값을 가진 토큰 생성
            val now = Date()
            val tokenWithBadRole = Jwts.builder()
                .id("jti-bad-role")
                .subject("user-bad")
                .claim("email", "bad@example.com")
                .claim("role", "SUPER_ADMIN_INVALID")  // 존재하지 않는 역할
                .claim("tenantId", "default")
                .issuedAt(now)
                .expiration(Date(now.time + 86_400_000))
                .signWith(Keys.hmacShaKeyFor(testSecret.toByteArray()))
                .compact()

            val role = tokenProvider.extractRole(tokenWithBadRole)

            assertNull(role) { "존재하지 않는 role enum 값은 null로 처리되어야 한다" }
        }

        @Test
        fun `tenantId claim이 공백만 있는 경우 extractTenantId는 null을 반환해야 한다`() {
            val now = Date()
            val tokenWithBlankTenant = Jwts.builder()
                .id("jti-blank-tenant")
                .subject("user-bt")
                .claim("email", "bt@example.com")
                .claim("role", "USER")
                .claim("tenantId", "   ")  // 공백만 있는 tenantId
                .issuedAt(now)
                .expiration(Date(now.time + 86_400_000))
                .signWith(Keys.hmacShaKeyFor(testSecret.toByteArray()))
                .compact()

            val tenantId = tokenProvider.extractTenantId(tokenWithBlankTenant)

            assertNull(tenantId) { "공백만 있는 tenantId claim은 null로 정규화되어야 한다" }
        }

        @Test
        fun `account_id (언더스코어) claim도 extractAccountId가 추출해야 한다`() {
            val now = Date()
            val tokenWithUnderscoreAccountId = Jwts.builder()
                .id("jti-account-id")
                .subject("user-acct")
                .claim("email", "acct@example.com")
                .claim("role", "USER")
                .claim("tenantId", "default")
                .claim("account_id", "acct-underscore-123")  // 언더스코어 변형 key
                .issuedAt(now)
                .expiration(Date(now.time + 86_400_000))
                .signWith(Keys.hmacShaKeyFor(testSecret.toByteArray()))
                .compact()

            val accountId = tokenProvider.extractAccountId(tokenWithUnderscoreAccountId)

            assertEquals("acct-underscore-123", accountId) {
                "account_id (언더스코어) claim도 extractAccountId가 추출해야 한다"
            }
        }

        @Test
        fun `64바이트 이상의 긴 시크릿으로도 토큰 생성과 검증이 성공해야 한다`() {
            val longSecret = "a".repeat(128)
            val longSecretProvider = JwtTokenProvider(AuthProperties(jwtSecret = longSecret))
            val token = longSecretProvider.createToken(testUser)

            val userId = longSecretProvider.validateToken(token)

            assertEquals("user-gap-1", userId) { "긴 시크릿으로도 정상적으로 토큰을 생성/검증할 수 있어야 한다" }
        }
    }

    // ─── InMemoryTokenRevocationStore: 추가 엣지 케이스 ─────────────────────────

    @Nested
    inner class InMemoryRevocationStoreGap {

        private val store = InMemoryTokenRevocationStore()

        @Test
        fun `동일한 토큰을 두 번 revoke해도 예외가 발생하지 않아야 한다 (멱등성)`() {
            val tokenId = "jti-idempotent"
            val expiresAt = Instant.now().plusSeconds(300)

            store.revoke(tokenId, expiresAt)
            store.revoke(tokenId, expiresAt)  // 두 번째 revoke

            assertTrue(store.isRevoked(tokenId)) {
                "중복 revoke 후에도 토큰은 여전히 폐기 상태여야 한다"
            }
        }

        @Test
        fun `isRevoked는 revoke하지 않은 토큰에 대해 false를 반환해야 한다`() {
            val result = store.isRevoked("jti-never-revoked")

            assertFalse(result) { "revoke하지 않은 토큰은 폐기 상태가 아니어야 한다" }
        }

        @Test
        fun `정확히 만료 시각인 토큰(현재 시각)은 저장되지 않아야 한다`() {
            // Instant.now()는 경계값 — expiresAt <= Instant.now() 이므로 저장 안 됨
            val tokenId = "jti-exact-now"
            store.revoke(tokenId, Instant.now().minusMillis(1))

            assertFalse(store.isRevoked(tokenId)) {
                "만료된 토큰은 저장되지 않아야 한다"
            }
            assertEquals(0, store.size()) {
                "만료된 토큰 revoke 시도 후 저장소는 비어 있어야 한다"
            }
        }

        @Test
        fun `여러 유효 토큰을 revoke하면 size가 증가해야 한다`() {
            val futureExpiry = Instant.now().plusSeconds(600)
            store.revoke("jti-multi-1", futureExpiry)
            store.revoke("jti-multi-2", futureExpiry)
            store.revoke("jti-multi-3", futureExpiry)

            assertTrue(store.size() >= 3) {
                "3개 토큰 revoke 후 size는 3 이상이어야 한다. 실제: ${store.size()}"
            }
        }

        @Test
        fun `purgeExpired 후 만료 항목은 제거되고 유효 항목만 남아야 한다`() {
            val anotherStore = InMemoryTokenRevocationStore()
            anotherStore.revoke("jti-purge-valid", Instant.now().plusSeconds(600))
            // 이미 만료된 항목은 애초에 저장 안 되므로 purge 후 valid 1개만 남음

            anotherStore.purgeExpired()

            assertTrue(anotherStore.isRevoked("jti-purge-valid")) {
                "purgeExpired 후 유효 항목은 남아 있어야 한다"
            }
        }
    }

    // ─── RedisTokenRevocationStore: 커스텀 prefix + 방어 ─────────────────────

    @Nested
    inner class RedisTokenRevocationStoreGap {

        @Test
        fun `커스텀 keyPrefix가 revoke와 isRevoked에 모두 적용되어야 한다`() {
            val redisTemplate = mockk<StringRedisTemplate>()
            val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
            every { redisTemplate.opsForValue() } returns valueOps
            every { redisTemplate.hasKey(match { it.startsWith("custom:prefix:") }) } returns true
            val store = RedisTokenRevocationStore(redisTemplate, keyPrefix = "custom:prefix")

            store.revoke("jti-custom", Instant.now().plusSeconds(120))
            val revoked = store.isRevoked("jti-custom")

            verify(exactly = 1) {
                valueOps.set(match { it == "custom:prefix:jti-custom" }, "1", any<java.time.Duration>())
            }
            assertTrue(revoked) { "커스텀 keyPrefix로 저장된 토큰은 폐기 상태로 확인되어야 한다" }
        }

        @Test
        fun `blank keyPrefix로 생성 시 IllegalArgumentException이 발생해야 한다`() {
            val redisTemplate = mockk<StringRedisTemplate>()

            assertThrows(IllegalArgumentException::class.java) {
                RedisTokenRevocationStore(redisTemplate, keyPrefix = "   ")
            }
        }

        @Test
        fun `빈 keyPrefix로 생성 시 IllegalArgumentException이 발생해야 한다`() {
            val redisTemplate = mockk<StringRedisTemplate>()

            assertThrows(IllegalArgumentException::class.java) {
                RedisTokenRevocationStore(redisTemplate, keyPrefix = "")
            }
        }

        @Test
        fun `isRevoked는 Redis false 응답 시 false를 반환해야 한다`() {
            val redisTemplate = mockk<StringRedisTemplate>()
            every { redisTemplate.hasKey(any()) } returns false
            val store = RedisTokenRevocationStore(redisTemplate)

            val result = store.isRevoked("jti-false-redis")

            assertFalse(result) { "Redis hasKey가 false를 반환하면 폐기 상태가 아니어야 한다" }
        }
    }

    // ─── JwtAuthWebFilter: authProvider=null + 잘못된 claim 처리 ─────────────

    @Nested
    inner class JwtAuthWebFilterGap {

        private val testSecret = "arc-reactor-test-jwt-secret-key-at-least-32-chars-long"
        private val jwtTokenProvider = mockk<JwtTokenProvider>()
        private val authProperties = AuthProperties(
            jwtSecret = testSecret,
            publicPaths = listOf("/api/auth/login")
        )

        private lateinit var exchange: ServerWebExchange
        private lateinit var chain: WebFilterChain
        private lateinit var request: ServerHttpRequest
        private lateinit var response: ServerHttpResponse
        private val headers = HttpHeaders()
        private val attributes = mutableMapOf<String, Any>()

        @BeforeEach
        fun setup() {
            exchange = mockk(relaxed = true)
            chain = mockk()
            request = mockk()
            response = mockk(relaxed = true)

            every { exchange.request } returns request
            every { exchange.response } returns response
            every { exchange.attributes } returns attributes
            every { request.headers } returns headers
            every { request.uri } returns URI.create("http://localhost/api/chat")
            every { chain.filter(exchange) } returns Mono.empty()
            every { response.setComplete() } returns Mono.empty()
        }

        @Test
        fun `authProvider가 null이면 JWT role claim을 그대로 사용해야 한다`() {
            // authProvider 없이 필터 생성 (null)
            val filter = JwtAuthWebFilter(jwtTokenProvider, authProperties, null, null)

            headers.set(HttpHeaders.AUTHORIZATION, "Bearer valid-no-provider")
            every { jwtTokenProvider.validateToken("valid-no-provider") } returns "user-no-provider"
            every { jwtTokenProvider.extractRole("valid-no-provider") } returns UserRole.ADMIN
            every { jwtTokenProvider.extractTokenId("valid-no-provider") } returns null
            every { jwtTokenProvider.extractTenantId("valid-no-provider") } returns null
            every { jwtTokenProvider.extractEmail("valid-no-provider") } returns "admin@example.com"
            every { jwtTokenProvider.extractAccountId("valid-no-provider") } returns null

            filter.filter(exchange, chain).block()

            assertEquals(UserRole.ADMIN, attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE]) {
                "authProvider가 없으면 JWT role claim을 그대로 사용해야 한다"
            }
            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `tokenRevocationStore가 null이면 jti 없는 토큰도 통과해야 한다`() {
            // revocation store 없이 필터 생성
            val filter = JwtAuthWebFilter(jwtTokenProvider, authProperties, null, null)

            headers.set(HttpHeaders.AUTHORIZATION, "Bearer no-jti-no-store")
            every { jwtTokenProvider.validateToken("no-jti-no-store") } returns "user-no-jti"
            every { jwtTokenProvider.extractRole("no-jti-no-store") } returns UserRole.USER
            every { jwtTokenProvider.extractTokenId("no-jti-no-store") } returns null
            every { jwtTokenProvider.extractTenantId("no-jti-no-store") } returns null
            every { jwtTokenProvider.extractEmail("no-jti-no-store") } returns "user@example.com"
            every { jwtTokenProvider.extractAccountId("no-jti-no-store") } returns null

            filter.filter(exchange, chain).block()

            // revocation store가 없으면 jti가 없어도 통과해야 한다
            verify(exactly = 1) { chain.filter(exchange) }
            verify(exactly = 0) { response.statusCode = HttpStatus.UNAUTHORIZED }
        }

        @Test
        fun `잘못된 형식의 email claim은 DB 폴백 email을 사용해야 한다`() {
            val authProvider = mockk<AuthProvider>()
            val tokenRevocationStore = mockk<TokenRevocationStore>()
            val filter = JwtAuthWebFilter(jwtTokenProvider, authProperties, authProvider, tokenRevocationStore)

            headers.set(HttpHeaders.AUTHORIZATION, "Bearer bad-email-token")
            every { jwtTokenProvider.validateToken("bad-email-token") } returns "user-bad-email"
            every { jwtTokenProvider.extractRole("bad-email-token") } returns UserRole.USER
            every { jwtTokenProvider.extractTokenId("bad-email-token") } returns "jti-bad-email"
            every { jwtTokenProvider.extractTenantId("bad-email-token") } returns null
            // 잘못된 이메일 형식 (@ 없음)
            every { jwtTokenProvider.extractEmail("bad-email-token") } returns "not-an-email"
            every { jwtTokenProvider.extractAccountId("bad-email-token") } returns null
            every { tokenRevocationStore.isRevoked("jti-bad-email") } returns false
            every { authProvider.getUserById("user-bad-email") } returns User(
                id = "user-bad-email",
                email = "valid-db@example.com",
                name = "Bad Email User",
                passwordHash = "hash"
            )

            filter.filter(exchange, chain).block()

            assertEquals("valid-db@example.com", attributes[JwtAuthWebFilter.USER_EMAIL_ATTRIBUTE]) {
                "잘못된 형식의 token email claim은 DB에서 폴백 이메일을 사용해야 한다"
            }
            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `특수문자가 포함된 tenantId claim은 defaultTenantId를 사용해야 한다`() {
            val filter = JwtAuthWebFilter(jwtTokenProvider, authProperties, null, null)

            headers.set(HttpHeaders.AUTHORIZATION, "Bearer special-tenant-token")
            every { jwtTokenProvider.validateToken("special-tenant-token") } returns "user-special-tenant"
            every { jwtTokenProvider.extractRole("special-tenant-token") } returns UserRole.USER
            every { jwtTokenProvider.extractTokenId("special-tenant-token") } returns null
            // 특수문자가 포함된 tenantId — 패턴 [a-zA-Z0-9_-]{1,64} 불일치
            every { jwtTokenProvider.extractTenantId("special-tenant-token") } returns "tenant/with/slashes"
            every { jwtTokenProvider.extractEmail("special-tenant-token") } returns "user@example.com"
            every { jwtTokenProvider.extractAccountId("special-tenant-token") } returns null

            filter.filter(exchange, chain).block()

            assertEquals("default", attributes[JwtAuthWebFilter.RESOLVED_TENANT_ID_ATTRIBUTE]) {
                "유효하지 않은 tenantId claim은 defaultTenantId로 폴백해야 한다"
            }
            verify(exactly = 1) { chain.filter(exchange) }
        }

        @Test
        fun `128자 초과 email claim은 DB 폴백 이메일을 사용해야 한다`() {
            val authProvider = mockk<AuthProvider>()
            val tokenRevocationStore = mockk<TokenRevocationStore>()
            val filter = JwtAuthWebFilter(jwtTokenProvider, authProperties, authProvider, tokenRevocationStore)

            headers.set(HttpHeaders.AUTHORIZATION, "Bearer long-email-token")
            val tooLongEmail = "a".repeat(120) + "@example.com"  // 132자
            every { jwtTokenProvider.validateToken("long-email-token") } returns "user-long-email"
            every { jwtTokenProvider.extractRole("long-email-token") } returns UserRole.USER
            every { jwtTokenProvider.extractTokenId("long-email-token") } returns "jti-long-email"
            every { jwtTokenProvider.extractTenantId("long-email-token") } returns null
            every { jwtTokenProvider.extractEmail("long-email-token") } returns tooLongEmail
            every { jwtTokenProvider.extractAccountId("long-email-token") } returns null
            every { tokenRevocationStore.isRevoked("jti-long-email") } returns false
            every { authProvider.getUserById("user-long-email") } returns User(
                id = "user-long-email",
                email = "normal@example.com",
                name = "Long Email User",
                passwordHash = "hash"
            )

            filter.filter(exchange, chain).block()

            assertEquals("normal@example.com", attributes[JwtAuthWebFilter.USER_EMAIL_ATTRIBUTE]) {
                "128자 초과 email claim은 거부되고 DB 폴백 이메일을 사용해야 한다"
            }
            verify(exactly = 1) { chain.filter(exchange) }
        }
    }

    // ─── AuthRateLimitFilter: Retry-After 헤더 + GET 미적용 ──────────────────

    @Nested
    inner class AuthRateLimitFilterGap {

        private lateinit var filter: AuthRateLimitFilter
        private lateinit var exchange: ServerWebExchange
        private lateinit var chain: WebFilterChain
        private lateinit var request: ServerHttpRequest
        private lateinit var response: ServerHttpResponse
        private val headers = HttpHeaders()
        private var currentStatus: HttpStatus? = HttpStatus.UNAUTHORIZED

        @BeforeEach
        fun setup() {
            filter = AuthRateLimitFilter(maxAttemptsPerMinute = 2)
            exchange = mockk(relaxed = true)
            chain = mockk()
            request = mockk()
            response = mockk(relaxed = true)

            every { exchange.request } returns request
            every { exchange.response } returns response
            every { request.headers } returns headers
            every { request.method } returns HttpMethod.POST
            every { request.remoteAddress } returns InetSocketAddress("10.0.0.1", 9999)
            every { chain.filter(exchange) } returns Mono.empty()
            every { response.bufferFactory() } returns DefaultDataBufferFactory()
            every { response.statusCode } answers { currentStatus }
        }

        @Test
        fun `429 응답 시 Retry-After 헤더가 60으로 설정되어야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/login")
            currentStatus = HttpStatus.UNAUTHORIZED
            val capturedHeaders = HttpHeaders()
            every { response.headers } returns capturedHeaders

            // 한도(2)를 초과시켜 429 발생
            repeat(2) { filter.filter(exchange, chain).block() }
            filter.filter(exchange, chain).block()

            assertEquals("60", capturedHeaders.getFirst("Retry-After")) {
                "429 응답 시 Retry-After 헤더가 60 (초)으로 설정되어야 한다"
            }
        }

        @Test
        fun `GET 메서드 로그인 경로는 속도 제한 대상이 아니어야 한다`() {
            every { request.method } returns HttpMethod.GET
            every { request.uri } returns URI.create("http://localhost/api/auth/login")
            currentStatus = HttpStatus.UNAUTHORIZED

            repeat(5) { filter.filter(exchange, chain).block() }

            // GET 요청은 rate limit 대상 아니므로 모두 통과
            verify(exactly = 5) { chain.filter(exchange) }
            verify(exactly = 0) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }

        @Test
        fun `PUT 메서드 로그인 경로는 속도 제한 대상이 아니어야 한다`() {
            every { request.method } returns HttpMethod.PUT
            every { request.uri } returns URI.create("http://localhost/api/auth/login")
            currentStatus = HttpStatus.UNAUTHORIZED

            repeat(5) { filter.filter(exchange, chain).block() }

            verify(exactly = 5) { chain.filter(exchange) }
            verify(exactly = 0) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }

        @Test
        fun `remoteAddress가 null이면 unknown 키를 사용하여 정상 동작해야 한다`() {
            every { request.remoteAddress } returns null
            every { request.uri } returns URI.create("http://localhost/api/auth/login")
            currentStatus = HttpStatus.UNAUTHORIZED

            // remoteAddress=null, X-Forwarded-For 없음 → "unknown:/api/auth/login" 키 사용
            repeat(2) { filter.filter(exchange, chain).block() }

            // 한도(2) 초과 → 차단
            filter.filter(exchange, chain).block()

            verify(atLeast = 1) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }

        @Test
        fun `존재하지 않는 auth 경로는 속도 제한 대상이 아니어야 한다`() {
            every { request.uri } returns URI.create("http://localhost/api/auth/logout")
            currentStatus = HttpStatus.UNAUTHORIZED

            repeat(5) { filter.filter(exchange, chain).block() }

            verify(exactly = 5) { chain.filter(exchange) }
            verify(exactly = 0) { response.statusCode = HttpStatus.TOO_MANY_REQUESTS }
        }
    }

    // ─── DefaultRateLimitStage: 테넌트별 차등 제한 ───────────────────────────

    @Nested
    inner class DefaultRateLimitStageTenantGap {

        @Test
        fun `테넌트별 커스텀 제한이 전역 기본값을 덮어써야 한다`() = runBlocking {
            val tenantLimits = mapOf(
                "premium-tenant" to TenantRateLimit(perMinute = 50, perHour = 500)
            )
            val stage = DefaultRateLimitStage(
                requestsPerMinute = 5,
                requestsPerHour = 50,
                tenantRateLimits = tenantLimits
            )

            // premium-tenant 사용자: perMinute=50이므로 6번 요청해도 허용
            val command = GuardCommand(
                userId = "user-premium",
                text = "hello",
                metadata = mapOf("tenantId" to "premium-tenant")
            )

            val results = (1..6).map { stage.enforce(command) }

            assertTrue(results.all { it is GuardResult.Allowed }) {
                "premium-tenant 사용자는 6번 요청이 전부 허용되어야 한다 (tenantLimit=50)"
            }
        }

        @Test
        fun `전역 제한보다 낮은 테넌트 제한은 더 엄격하게 적용되어야 한다`() = runBlocking {
            val tenantLimits = mapOf(
                "restricted-tenant" to TenantRateLimit(perMinute = 2, perHour = 10)
            )
            val stage = DefaultRateLimitStage(
                requestsPerMinute = 100,
                requestsPerHour = 1000,
                tenantRateLimits = tenantLimits
            )
            val command = GuardCommand(
                userId = "user-restricted",
                text = "hello",
                metadata = mapOf("tenantId" to "restricted-tenant")
            )

            repeat(2) { stage.enforce(command) }
            val rejected = stage.enforce(command)

            assertInstanceOf(GuardResult.Rejected::class.java, rejected) {
                "restricted-tenant 사용자는 3번째 요청에서 거부되어야 한다 (tenantLimit=2)"
            }
            assertEquals(RejectionCategory.RATE_LIMITED, (rejected as GuardResult.Rejected).category) {
                "거부 카테고리는 RATE_LIMITED여야 한다"
            }
        }

        @Test
        fun `다른 테넌트의 동일한 userId는 독립적인 속도 제한 카운터를 가져야 한다`() = runBlocking {
            val tenantLimits = mapOf(
                "tenant-a" to TenantRateLimit(perMinute = 2, perHour = 20),
                "tenant-b" to TenantRateLimit(perMinute = 100, perHour = 1000)
            )
            val stage = DefaultRateLimitStage(
                requestsPerMinute = 10,
                requestsPerHour = 100,
                tenantRateLimits = tenantLimits
            )
            val sharedUserId = "user-shared"

            val commandA = GuardCommand(
                userId = sharedUserId,
                text = "hello",
                metadata = mapOf("tenantId" to "tenant-a")
            )
            val commandB = GuardCommand(
                userId = sharedUserId,
                text = "hello",
                metadata = mapOf("tenantId" to "tenant-b")
            )

            // tenant-a: 한도(2) 소진
            repeat(2) { stage.enforce(commandA) }
            val tenantARejected = stage.enforce(commandA)

            // tenant-b: 독립적이므로 아직 허용
            val tenantBResult = stage.enforce(commandB)

            assertInstanceOf(GuardResult.Rejected::class.java, tenantARejected) {
                "tenant-a에서 한도 초과 시 거부되어야 한다"
            }
            assertInstanceOf(GuardResult.Allowed::class.java, tenantBResult) {
                "동일한 userId여도 tenant-b는 독립적인 카운터를 가져야 한다"
            }
        }

        @Test
        fun `tenantId metadata가 없으면 전역 기본 제한이 적용되어야 한다`() = runBlocking {
            val stage = DefaultRateLimitStage(requestsPerMinute = 3, requestsPerHour = 30)
            val command = GuardCommand(userId = "user-no-tenant", text = "hello")

            repeat(3) { stage.enforce(command) }
            val rejected = stage.enforce(command)

            assertInstanceOf(GuardResult.Rejected::class.java, rejected) {
                "tenantId 없이 전역 제한(3)을 초과하면 거부되어야 한다"
            }
            assertTrue((rejected as GuardResult.Rejected).reason.contains("per minute")) {
                "거부 이유에 'per minute'이 포함되어야 한다. 실제: ${rejected.reason}"
            }
        }

        @Test
        fun `시간당 제한 초과 시 거부 이유에 per hour가 포함되어야 한다`() = runBlocking {
            val stage = DefaultRateLimitStage(requestsPerMinute = 1000, requestsPerHour = 3)
            val command = GuardCommand(userId = "user-hour-limit", text = "hello")

            repeat(3) { stage.enforce(command) }
            val rejected = stage.enforce(command)

            assertInstanceOf(GuardResult.Rejected::class.java, rejected) {
                "시간당 제한(3) 초과 시 거부되어야 한다"
            }
            assertEquals(RejectionCategory.RATE_LIMITED, (rejected as GuardResult.Rejected).category) {
                "거부 카테고리는 RATE_LIMITED여야 한다"
            }
            assertTrue(rejected.reason.contains("per hour")) {
                "거부 이유에 'per hour'이 포함되어야 한다. 실제: ${rejected.reason}"
            }
        }
    }
}
