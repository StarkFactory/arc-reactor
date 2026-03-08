package com.arc.reactor.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Date

class JwtTokenProviderTest {

    private val testSecret = "arc-reactor-test-jwt-secret-key-at-least-32-chars-long"

    private lateinit var tokenProvider: JwtTokenProvider

    private val testUser = User(
        id = "user-42",
        email = "tony@stark.com",
        name = "Tony Stark",
        passwordHash = "not-relevant-for-jwt"
    )

    @BeforeEach
    fun setup() {
        tokenProvider = JwtTokenProvider(
            AuthProperties(
                jwtSecret = testSecret,
                jwtExpirationMs = 86_400_000
            )
        )
    }

    @Nested
    inner class CreateToken {

        @Test
        fun `should create a valid non-empty token`() {
            val token = tokenProvider.createToken(testUser)

            assertNotNull(token) { "Token should not be null" }
            assertTrue(token.isNotBlank()) { "Token should not be blank" }
            // JWT has 3 dot-separated parts
            assertEquals(3, token.split(".").size) { "JWT should have 3 parts separated by dots" }
        }

        @Test
        fun `token should contain userId as subject`() {
            val token = tokenProvider.createToken(testUser)
            val extractedUserId = tokenProvider.validateToken(token)

            assertEquals("user-42", extractedUserId) { "Subject (userId) should match the user ID" }
        }

        @Test
        fun `token should contain email claim`() {
            val token = tokenProvider.createToken(testUser)

            // Validate token returns userId, proving the token is parseable
            val userId = tokenProvider.validateToken(token)
            assertNotNull(userId) { "Token should be valid and parseable" }

            // Decode the payload manually to check email claim
            val payloadPart = token.split(".")[1]
            val payload = String(
                java.util.Base64.getUrlDecoder().decode(payloadPart),
                Charsets.UTF_8
            )
            assertTrue(payload.contains("tony@stark.com")) {
                "Token payload should contain the email claim, got: $payload"
            }
        }
    }

    @Nested
    inner class RoleClaim {

        @Test
        fun `token should contain role claim`() {
            val adminUser = testUser.copy(role = UserRole.ADMIN)
            val token = tokenProvider.createToken(adminUser)

            val role = tokenProvider.extractRole(token)

            assertEquals(UserRole.ADMIN, role) { "extractRole should return ADMIN for admin user" }
        }

        @Test
        fun `token should preserve ADMIN_MANAGER role claim`() {
            val managerAdmin = testUser.copy(role = UserRole.ADMIN_MANAGER)
            val token = tokenProvider.createToken(managerAdmin)

            val role = tokenProvider.extractRole(token)

            assertEquals(UserRole.ADMIN_MANAGER, role) {
                "extractRole should return ADMIN_MANAGER for manager-scope admin user"
            }
        }

        @Test
        fun `default user should have USER role in token`() {
            val token = tokenProvider.createToken(testUser)

            val role = tokenProvider.extractRole(token)

            assertEquals(UserRole.USER, role) { "extractRole should return USER for default user" }
        }

        @Test
        fun `extractRole should return null for invalid token`() {
            val role = tokenProvider.extractRole("invalid.token.value")

            assertNull(role) { "extractRole should return null for invalid token" }
        }
    }

    @Nested
    inner class TenantClaim {

        @Test
        fun `token should contain default tenant claim`() {
            val token = tokenProvider.createToken(testUser)

            val tenantId = tokenProvider.extractTenantId(token)

            assertEquals("default", tenantId) {
                "extractTenantId should return default tenant claim when issuing tokens"
            }
        }

        @Test
        fun `extractTenantId should return null for invalid token`() {
            val tenantId = tokenProvider.extractTenantId("invalid.token.value")

            assertNull(tenantId) { "extractTenantId should return null for invalid token" }
        }
    }

    @Nested
    inner class EmailClaim {

        @Test
        fun `token should expose email claim`() {
            val token = tokenProvider.createToken(testUser)

            val email = tokenProvider.extractEmail(token)

            assertEquals("tony@stark.com", email) { "extractEmail should return the encoded email claim" }
        }

        @Test
        fun `extractEmail should return null for invalid token`() {
            val email = tokenProvider.extractEmail("invalid.token.value")

            assertNull(email) { "extractEmail should return null for invalid token" }
        }
    }

    @Nested
    inner class AccountClaim {

        @Test
        fun `token should expose accountId claim`() {
            val tokenWithAccount = createJwtWithAccountClaim("user-42", "acct-42")

            val accountId = tokenProvider.extractAccountId(tokenWithAccount)

            assertEquals("acct-42", accountId) { "extractAccountId should return the encoded accountId claim" }
        }

        @Test
        fun `extractAccountId should return null for invalid token`() {
            val accountId = tokenProvider.extractAccountId("invalid.token.value")

            assertNull(accountId) { "extractAccountId should return null for invalid token" }
        }

        private fun createJwtWithAccountClaim(subject: String, accountId: String): String {
            val now = Date()
            return io.jsonwebtoken.Jwts.builder()
                .id("acct-test")
                .subject(subject)
                .claim("email", "tony@stark.com")
                .claim("role", "USER")
                .claim("tenantId", "default")
                .claim("accountId", accountId)
                .issuedAt(now)
                .expiration(Date(now.time + 86_400_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(testSecret.toByteArray()))
                .compact()
        }
    }

    @Nested
    inner class SecretValidation {

        @Test
        fun `should reject secret shorter than 32 bytes`() {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                JwtTokenProvider(
                    AuthProperties(jwtSecret = "too-short")
                )
            }
            assertTrue(exception.message!!.contains("at least 32 bytes")) {
                "Error message should mention minimum length, got: ${exception.message}"
            }
        }

        @Test
        fun `should reject empty secret`() {
            assertThrows(IllegalArgumentException::class.java) {
                JwtTokenProvider(
                    AuthProperties(jwtSecret = "")
                )
            }
        }

        @Test
        fun `should accept secret of exactly 32 bytes`() {
            val provider = JwtTokenProvider(
                AuthProperties(jwtSecret = "a".repeat(32))
            )
            val token = provider.createToken(testUser)
            assertNotNull(token) { "Should create token with 32-byte secret" }
        }
    }

    @Nested
    inner class ValidateToken {

        @Test
        fun `should validate and return userId for valid token`() {
            val token = tokenProvider.createToken(testUser)

            val result = tokenProvider.validateToken(token)

            assertEquals("user-42", result) { "validateToken should return the userId" }
        }

        @Test
        fun `should return null for invalid token`() {
            val result = tokenProvider.validateToken("invalid.token.value")

            assertNull(result) { "validateToken should return null for invalid token" }
        }

        @Test
        fun `should return null for expired token`() {
            // Create a provider with 1ms expiration
            val shortLivedProvider = JwtTokenProvider(
                AuthProperties(
                    jwtSecret = testSecret,
                    jwtExpirationMs = 1
                )
            )

            val token = shortLivedProvider.createToken(testUser)

            // Wait for expiration
            Thread.sleep(50)

            val result = shortLivedProvider.validateToken(token)

            assertNull(result) { "validateToken should return null for expired token" }
        }
    }
}
