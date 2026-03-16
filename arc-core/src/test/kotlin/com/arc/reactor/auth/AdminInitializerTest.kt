package com.arc.reactor.auth

import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * AdminInitializer에 대한 테스트.
 *
 * 관리자 초기화 로직을 검증합니다.
 */
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
        fun `email does not exist일 때 create admin해야 한다`() {
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
        fun `admin email already exists일 때 skip해야 한다`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_EMAIL"] = "admin@test.com"
            envVars["ARC_REACTOR_AUTH_ADMIN_PASSWORD"] = "securepass"
            every { userStore.existsByEmail("admin@test.com") } returns true

            createInitializer().initAdmin()

            verify(exactly = 0) { userStore.save(any()) }
        }

        @Test
        fun `env var not set일 때 use default name해야 한다`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_EMAIL"] = "admin@test.com"
            envVars["ARC_REACTOR_AUTH_ADMIN_PASSWORD"] = "securepass"
            // ARC_REACTOR_AUTH_ADMIN_NAME이 설정되지 않음
            every { userStore.existsByEmail("admin@test.com") } returns false
            every { authProvider.hashPassword("securepass") } returns "hashed"
            val slot = slot<User>()
            every { userStore.save(capture(slot)) } answers { slot.captured }

            createInitializer().initAdmin()

            assertEquals("Admin", slot.captured.name) { "Default name should be 'Admin'" }
        }
    }

    @Nested
    inner class PasswordValidation {

        @Test
        fun `password is shorter than 8 characters일 때 skip해야 한다`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_EMAIL"] = "admin@test.com"
            envVars["ARC_REACTOR_AUTH_ADMIN_PASSWORD"] = "short"

            createInitializer().initAdmin()

            verify(exactly = 0) { userStore.save(any()) }
        }

        @Test
        fun `exactly 8 characters로 accept password해야 한다`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_EMAIL"] = "admin@test.com"
            envVars["ARC_REACTOR_AUTH_ADMIN_PASSWORD"] = "12345678"
            every { userStore.existsByEmail("admin@test.com") } returns false
            every { authProvider.hashPassword("12345678") } returns "hashed"
            every { userStore.save(any()) } answers { firstArg() }

            createInitializer().initAdmin()

            verify(exactly = 1) { userStore.save(any()) }
        }
    }

    @Nested
    inner class WhenEnvVarsNotSet {

        @Test
        fun `email env var is missing일 때 skip해야 한다`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_PASSWORD"] = "securepass"
            // ARC_REACTOR_AUTH_ADMIN_EMAIL이 설정되지 않음

            createInitializer().initAdmin()

            verify(exactly = 0) { userStore.save(any()) }
        }

        @Test
        fun `password env var is missing일 때 skip해야 한다`() {
            envVars["ARC_REACTOR_AUTH_ADMIN_EMAIL"] = "admin@test.com"
            // ARC_REACTOR_AUTH_ADMIN_PASSWORD이 설정되지 않음

            createInitializer().initAdmin()

            verify(exactly = 0) { userStore.save(any()) }
        }
    }
}
