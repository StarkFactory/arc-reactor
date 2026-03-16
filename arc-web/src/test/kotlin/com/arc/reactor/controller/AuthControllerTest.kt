package com.arc.reactor.controller

import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.AuthProvider
import com.arc.reactor.auth.DefaultAuthProvider
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.TokenRevocationStore
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.arc.reactor.auth.UserStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.just
import io.mockk.Runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

class AuthControllerTest {

    private lateinit var authProvider: DefaultAuthProvider
    private lateinit var userStore: UserStore
    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var tokenRevocationStore: TokenRevocationStore
    private lateinit var authProperties: AuthProperties
    private lateinit var controller: AuthController

    @BeforeEach
    fun setup() {
        authProvider = mockk()
        userStore = mockk()
        jwtTokenProvider = mockk()
        tokenRevocationStore = mockk(relaxed = true)
        authProperties = AuthProperties(selfRegistrationEnabled = true)
        controller = AuthController(authProvider, userStore, jwtTokenProvider, authProperties, tokenRevocationStore)
    }

    @Nested
    inner class Register {

        @Test
        fun `return 201 on successful registration해야 한다`() {
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
            assertEquals("USER", response.body!!.user!!.role) { "Registered user should always be USER" }
            assertNull(response.body!!.user!!.adminScope) { "Registered non-admin user should not have adminScope" }
            assertNull(response.body!!.error) { "Error should be null on success" }
        }

        @Test
        fun `duplicate email에 대해 return 409해야 한다`() {
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
        fun `registered user에 대해 create token해야 한다`() {
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

        @Test
        fun `self-registration is disabled일 때 return 403해야 한다`() {
            val disabledController = AuthController(
                authProvider = authProvider,
                userStore = userStore,
                jwtTokenProvider = jwtTokenProvider,
                authProperties = AuthProperties(selfRegistrationEnabled = false),
                tokenRevocationStore = tokenRevocationStore
            )
            val request = RegisterRequest(
                email = "blocked@test.com",
                password = "password123",
                name = "Blocked User"
            )
            val response = disabledController.register(request)

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Disabled self-registration should return 403 FORBIDDEN"
            }
            assertTrue(response.body?.error?.contains("disabled", ignoreCase = true) == true) {
                "Error message should explain that self-registration is disabled"
            }
        }

        @Test
        fun `custom auth provider does not support registration일 때 return 400해야 한다`() {
            val customProvider = mockk<AuthProvider>()
            val customController = AuthController(
                authProvider = customProvider,
                userStore = userStore,
                jwtTokenProvider = jwtTokenProvider,
                authProperties = AuthProperties(selfRegistrationEnabled = true),
                tokenRevocationStore = tokenRevocationStore
            )
            every { userStore.existsByEmail("custom@test.com") } returns false

            val response = customController.register(
                RegisterRequest(
                    email = "custom@test.com",
                    password = "password123",
                    name = "Custom User"
                )
            )

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "Custom AuthProvider registration should return 400 BAD_REQUEST"
            }
            assertTrue(response.body?.error?.contains("not supported", ignoreCase = true) == true) {
                "Error should explain that self-registration is unsupported for the configured provider"
            }
            verify(exactly = 0) { userStore.save(any()) }
            verify(exactly = 0) { jwtTokenProvider.createToken(any()) }
        }
    }

    @Nested
    inner class Login {

        @Test
        fun `return token on successful login해야 한다`() {
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
        fun `wrong password에 대해 return 401해야 한다`() {
            every { authProvider.authenticate("tony@stark.com", "wrong-pw") } returns null

            val request = LoginRequest(email = "tony@stark.com", password = "wrong-pw")
            val response = controller.login(request)

            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode) {
                "Wrong password should return 401 UNAUTHORIZED"
            }
            assertNotNull(response.body!!.error) { "Error message should be present" }
        }

        @Test
        fun `unknown email에 대해 return 401해야 한다`() {
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
        fun `userId in exchange일 때 return user profile해야 한다`() {
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
            val body = response.body!! as UserResponse
            assertEquals("user-1", body.id) { "User ID should match" }
            assertEquals("tony@stark.com", body.email) { "Email should match" }
            assertEquals("Tony Stark", body.name) { "Name should match" }
            assertNull(body.adminScope) { "USER role should not have adminScope in response" }
        }

        @Test
        fun `manager admin에 대해 return adminScope해야 한다`() {
            val exchange = mockk<ServerWebExchange>()
            val attributes = mutableMapOf<String, Any>(
                JwtAuthWebFilter.USER_ID_ATTRIBUTE to "admin-manager-1"
            )
            every { exchange.attributes } returns attributes

            val managerUser = User(
                id = "admin-manager-1",
                email = "manager@stark.com",
                name = "Manager Admin",
                passwordHash = "hashed",
                role = UserRole.ADMIN_MANAGER
            )
            every { authProvider.getUserById("admin-manager-1") } returns managerUser

            val response = controller.me(exchange)

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200 OK" }
            val body = response.body!! as UserResponse
            assertEquals("ADMIN_MANAGER", body.role) { "Role should match ADMIN_MANAGER" }
            assertEquals("MANAGER", body.adminScope) {
                "Manager admin role should return MANAGER adminScope"
            }
        }

        @Test
        fun `no userId in exchange일 때 return 401해야 한다`() {
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

    @Nested
    inner class Logout {

        @Test
        fun `bearer token is provided일 때 revoke token and return 200해야 한다`() {
            val exchange = mockk<ServerWebExchange>()
            val request = mockk<ServerHttpRequest>()
            val headers = HttpHeaders().apply {
                set(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
            }
            every { exchange.request } returns request
            every { request.headers } returns headers
            every { jwtTokenProvider.extractTokenId("valid-token") } returns "jti-123"
            val expiresAt = Instant.now().plusSeconds(3600)
            every { jwtTokenProvider.extractExpiration("valid-token") } returns expiresAt
            every { tokenRevocationStore.revoke("jti-123", expiresAt) } just Runs

            val response = controller.logout(exchange)

            assertEquals(HttpStatus.OK, response.statusCode) { "Logout should return 200 OK" }
            verify(exactly = 1) { tokenRevocationStore.revoke("jti-123", expiresAt) }
        }

        @Test
        fun `authorization header is missing일 때 return 401해야 한다`() {
            val exchange = mockk<ServerWebExchange>()
            val request = mockk<ServerHttpRequest>()
            every { exchange.request } returns request
            every { request.headers } returns HttpHeaders()

            val response = controller.logout(exchange)

            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode) {
                "Missing Authorization header should return 401 UNAUTHORIZED"
            }
        }
    }
}
