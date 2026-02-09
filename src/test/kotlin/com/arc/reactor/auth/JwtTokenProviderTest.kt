package com.arc.reactor.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
                enabled = true,
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
                    enabled = true,
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
