package com.arc.reactor.controller

import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.AuthProvider
import com.arc.reactor.auth.DefaultAuthProvider
import com.arc.reactor.auth.InMemoryUserStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class AuthControllerTest {

    private lateinit var authProvider: DefaultAuthProvider
    private lateinit var userStore: UserStore
    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var controller: AuthController

    @BeforeEach
    fun setup() {
        authProvider = mockk()
        userStore = mockk()
        jwtTokenProvider = mockk()
        controller = AuthController(authProvider, userStore, jwtTokenProvider)
    }

    @Nested
    inner class Register {

        @Test
        fun `should return 201 on successful registration`() {
            every { userStore.existsByEmail("new@test.com") } returns false
            every { authProvider.hashPassword("password123") } returns "hashed-pw"
            every { userStore.save(any()) } answers { firstArg() }
            every { jwtTokenProvider.createToken(any()) } returns "jwt-token-123"

            val request = RegisterRequest(
                email = "new@test.com",
                password = "password123",
                name = "New User"
            )
            val response = controller.register(request)

            assertEquals(HttpStatus.CREATED, response.statusCode) {
                "Successful registration should return 201 CREATED"
            }
            assertNotNull(response.body) { "Response body should not be null" }
            assertEquals("jwt-token-123", response.body!!.token) { "Token should match" }
            assertNotNull(response.body!!.user) { "User response should not be null" }
            assertEquals("new@test.com", response.body!!.user!!.email) { "Email should match" }
            assertEquals("New User", response.body!!.user!!.name) { "Name should match" }
            assertNull(response.body!!.error) { "Error should be null on success" }
        }

        @Test
        fun `should return 409 for duplicate email`() {
            every { userStore.existsByEmail("dup@test.com") } returns true

            val request = RegisterRequest(
                email = "dup@test.com",
                password = "password123",
                name = "Dup User"
            )
            val response = controller.register(request)

            assertEquals(HttpStatus.CONFLICT, response.statusCode) {
                "Duplicate email should return 409 CONFLICT"
            }
            assertNotNull(response.body!!.error) { "Error message should be present" }
            assertTrue(response.body!!.error!!.contains("already registered")) {
                "Error should mention already registered, got: ${response.body!!.error}"
            }
        }

        @Test
        fun `should create token for registered user`() {
            every { userStore.existsByEmail("token@test.com") } returns false
            every { authProvider.hashPassword("password123") } returns "hashed"
            every { userStore.save(any()) } answers { firstArg() }
            every { jwtTokenProvider.createToken(any()) } returns "fresh-token"

            val request = RegisterRequest(
                email = "token@test.com",
                password = "password123",
                name = "Token User"
            )
            val response = controller.register(request)

            assertEquals("fresh-token", response.body!!.token) { "Token should be returned in response" }
            verify(exactly = 1) { jwtTokenProvider.createToken(any()) }
        }
    }

    @Nested
    inner class Login {

        @Test
        fun `should return token on successful login`() {
            val user = User(
                id = "user-1",
                email = "tony@stark.com",
                name = "Tony Stark",
                passwordHash = "hashed"
            )
            every { authProvider.authenticate("tony@stark.com", "correct-pw") } returns user
            every { jwtTokenProvider.createToken(user) } returns "login-token"

            val request = LoginRequest(email = "tony@stark.com", password = "correct-pw")
            val response = controller.login(request)

            assertEquals(HttpStatus.OK, response.statusCode) { "Successful login should return 200 OK" }
            assertEquals("login-token", response.body!!.token) { "Token should match" }
            assertNotNull(response.body!!.user) { "User response should not be null" }
            assertEquals("tony@stark.com", response.body!!.user!!.email) { "Email should match" }
        }

        @Test
        fun `should return 401 for wrong password`() {
            every { authProvider.authenticate("tony@stark.com", "wrong-pw") } returns null

            val request = LoginRequest(email = "tony@stark.com", password = "wrong-pw")
            val response = controller.login(request)

            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode) {
                "Wrong password should return 401 UNAUTHORIZED"
            }
            assertNotNull(response.body!!.error) { "Error message should be present" }
        }

        @Test
        fun `should return 401 for unknown email`() {
            every { authProvider.authenticate("unknown@test.com", "any-pw") } returns null

            val request = LoginRequest(email = "unknown@test.com", password = "any-pw")
            val response = controller.login(request)

            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode) {
                "Unknown email should return 401 UNAUTHORIZED"
            }
        }
    }

    @Nested
    inner class Me {

        @Test
        fun `should return user profile when userId in exchange`() {
            val exchange = mockk<ServerWebExchange>()
            val attributes = mutableMapOf<String, Any>(
                JwtAuthWebFilter.USER_ID_ATTRIBUTE to "user-1"
            )
            every { exchange.attributes } returns attributes

            val user = User(
                id = "user-1",
                email = "tony@stark.com",
                name = "Tony Stark",
                passwordHash = "hashed"
            )
            every { authProvider.getUserById("user-1") } returns user

            val response = controller.me(exchange)

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200 OK" }
            assertNotNull(response.body) { "Response body should not be null" }
            assertEquals("user-1", response.body!!.id) { "User ID should match" }
            assertEquals("tony@stark.com", response.body!!.email) { "Email should match" }
            assertEquals("Tony Stark", response.body!!.name) { "Name should match" }
        }

        @Test
        fun `should return 401 when no userId in exchange`() {
            val exchange = mockk<ServerWebExchange>()
            val attributes = mutableMapOf<String, Any>()
            every { exchange.attributes } returns attributes

            val response = controller.me(exchange)

            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode) {
                "Missing userId should return 401 UNAUTHORIZED"
            }
            assertNull(response.body) { "Response body should be null for 401" }
        }
    }
}
