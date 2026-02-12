package com.arc.reactor.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class DefaultAuthProviderTest {

    private lateinit var userStore: InMemoryUserStore
    private lateinit var authProvider: DefaultAuthProvider
    private lateinit var testUser: User

    private val passwordEncoder = BCryptPasswordEncoder()
    private val rawPassword = "secure-password-123"

    @BeforeEach
    fun setup() {
        userStore = InMemoryUserStore()
        authProvider = DefaultAuthProvider(userStore)

        testUser = User(
            id = "user-1",
            email = "tony@stark.com",
            name = "Tony Stark",
            passwordHash = passwordEncoder.encode(rawPassword)
        )
        userStore.save(testUser)
    }

    @Nested
    inner class Authenticate {

        @Test
        fun `should return user for correct credentials`() {
            val result = authProvider.authenticate("tony@stark.com", rawPassword)

            assertNotNull(result) { "authenticate should return user for correct credentials" }
            assertEquals("user-1", result!!.id) { "Returned user ID should match" }
            assertEquals("tony@stark.com", result.email) { "Returned user email should match" }
            assertEquals("Tony Stark", result.name) { "Returned user name should match" }
        }

        @Test
        fun `should return null for wrong password`() {
            val result = authProvider.authenticate("tony@stark.com", "wrong-password")

            assertNull(result) { "authenticate should return null for wrong password" }
        }

        @Test
        fun `should return null for unknown email`() {
            val result = authProvider.authenticate("unknown@test.com", rawPassword)

            assertNull(result) { "authenticate should return null for unknown email" }
        }
    }

    @Nested
    inner class GetUserById {

        @Test
        fun `should return user by ID`() {
            val result = authProvider.getUserById("user-1")

            assertNotNull(result) { "getUserById should return user for existing ID" }
            assertEquals("user-1", result!!.id) { "User ID should match" }
            assertEquals("tony@stark.com", result.email) { "User email should match" }
        }
    }
}
