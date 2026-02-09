package com.arc.reactor.auth

import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AdminInitializerTest {

    private lateinit var userStore: UserStore
    private lateinit var authProvider: DefaultAuthProvider
    private lateinit var envVars: MutableMap<String, String?>

    @BeforeEach
    fun setup() {
        userStore = mockk(relaxed = true)
        authProvider = mockk()
        envVars = mutableMapOf()
    }

    private fun createInitializer(): AdminInitializer =
        AdminInitializer(userStore, authProvider) { envVars[it] }

    @Nested
    inner class WhenEnvVarsSet {

        @Test
        fun `should create admin when email does not exist`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_EMAIL"] = "admin@test.com"
            envVars["ARC_REACTOR_AUTH_ADMIN_PASSWORD"] = "securepass"
            envVars["ARC_REACTOR_AUTH_ADMIN_NAME"] = "Test Admin"
            every { userStore.existsByEmail("admin@test.com") } returns false
            every { authProvider.hashPassword("securepass") } returns "hashed"
            val slot = slot<User>()
            every { userStore.save(capture(slot)) } answers { slot.captured }

            createInitializer().initAdmin()

            verify(exactly = 1) { userStore.save(any()) }
            assertEquals("admin@test.com", slot.captured.email) { "Email should match env var" }
            assertEquals("Test Admin", slot.captured.name) { "Name should match env var" }
            assertEquals(UserRole.ADMIN, slot.captured.role) { "Role should be ADMIN" }
            assertEquals("hashed", slot.captured.passwordHash) { "Password should be hashed" }
        }

        @Test
        fun `should skip when admin email already exists`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_EMAIL"] = "admin@test.com"
            envVars["ARC_REACTOR_AUTH_ADMIN_PASSWORD"] = "securepass"
            every { userStore.existsByEmail("admin@test.com") } returns true

            createInitializer().initAdmin()

            verify(exactly = 0) { userStore.save(any()) }
        }

        @Test
        fun `should use default name when env var not set`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_EMAIL"] = "admin@test.com"
            envVars["ARC_REACTOR_AUTH_ADMIN_PASSWORD"] = "securepass"
            // ARC_REACTOR_AUTH_ADMIN_NAME not set
            every { userStore.existsByEmail("admin@test.com") } returns false
            every { authProvider.hashPassword("securepass") } returns "hashed"
            val slot = slot<User>()
            every { userStore.save(capture(slot)) } answers { slot.captured }

            createInitializer().initAdmin()

            assertEquals("Admin", slot.captured.name) { "Default name should be 'Admin'" }
        }
    }

    @Nested
    inner class WhenEnvVarsNotSet {

        @Test
        fun `should skip when email env var is missing`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_PASSWORD"] = "securepass"
            // ARC_REACTOR_AUTH_ADMIN_EMAIL not set

            createInitializer().initAdmin()

            verify(exactly = 0) { userStore.save(any()) }
        }

        @Test
        fun `should skip when password env var is missing`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_EMAIL"] = "admin@test.com"
            // ARC_REACTOR_AUTH_ADMIN_PASSWORD not set

            createInitializer().initAdmin()

            verify(exactly = 0) { userStore.save(any()) }
        }
    }
}
