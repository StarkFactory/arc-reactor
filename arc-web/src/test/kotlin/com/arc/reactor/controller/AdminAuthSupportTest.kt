package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class AdminAuthSupportTest {

    private fun exchangeWithRole(role: UserRole?): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attributes = mutableMapOf<String, Any>()
        if (role != null) {
            attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] = role
        }
        every { exchange.attributes } returns attributes
        return exchange
    }

    @Nested
    inner class IsAdmin {

        @Test
        fun `role is ADMIN일 때 true를 반환한다`() {
            assertTrue(isAdmin(exchangeWithRole(UserRole.ADMIN))) {
                "ADMIN role should be treated as admin"
            }
        }

        @Test
        fun `role is ADMIN_DEVELOPER일 때 true를 반환한다`() {
            assertTrue(isAdmin(exchangeWithRole(UserRole.ADMIN_DEVELOPER))) {
                "ADMIN_DEVELOPER role should be treated as developer admin"
            }
        }

        @Test
        fun `role is ADMIN_MANAGER일 때 false를 반환한다`() {
            assertFalse(isAdmin(exchangeWithRole(UserRole.ADMIN_MANAGER))) {
                "ADMIN_MANAGER role should not be treated as developer admin"
            }
        }

        @Test
        fun `role is USER일 때 false를 반환한다`() {
            assertFalse(isAdmin(exchangeWithRole(UserRole.USER))) {
                "USER role should not be treated as admin"
            }
        }

        @Test
        fun `role is null일 때 false를 반환한다`() {
            assertFalse(isAdmin(exchangeWithRole(null))) {
                "Null role should fail-close as non-admin"
            }
        }
    }

    @Nested
    inner class IsAnyAdmin {

        @Test
        fun `role is ADMIN_MANAGER일 때 true를 반환한다`() {
            assertTrue(isAnyAdmin(exchangeWithRole(UserRole.ADMIN_MANAGER))) {
                "ADMIN_MANAGER role should be treated as admin for manager surfaces"
            }
        }

        @Test
        fun `role is ADMIN_DEVELOPER일 때 true를 반환한다`() {
            assertTrue(isAnyAdmin(exchangeWithRole(UserRole.ADMIN_DEVELOPER))) {
                "ADMIN_DEVELOPER role should be treated as admin for manager surfaces"
            }
        }

        @Test
        fun `role is USER일 때 false를 반환한다`() {
            assertFalse(isAnyAdmin(exchangeWithRole(UserRole.USER))) {
                "USER role should not be treated as admin"
            }
        }
    }

    @Nested
    inner class ForbiddenResponse {

        @Test
        fun `403 with standard error body를 반환한다`() {
            val response = forbiddenResponse()

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "forbiddenResponse should return 403 status"
            }
            val body = response.body as ErrorResponse
            assertEquals("Admin access required", body.error) {
                "Error body should include standard admin access message"
            }
        }
    }

    @Nested
    inner class NotFoundResponse {

        @Test
        fun `404 with descriptive message를 반환한다`() {
            val response = notFoundResponse("Resource not found: abc")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "notFoundResponse should return 404 status"
            }
            val body = response.body as ErrorResponse
            assertEquals("Resource not found: abc", body.error) {
                "Error body should include the provided message"
            }
        }
    }

    @Nested
    inner class ConflictResponse {

        @Test
        fun `409 with descriptive message를 반환한다`() {
            val response = conflictResponse("Already exists")

            assertEquals(HttpStatus.CONFLICT, response.statusCode) {
                "conflictResponse should return 409 status"
            }
            val body = response.body as ErrorResponse
            assertEquals("Already exists", body.error) {
                "Error body should include the provided message"
            }
        }
    }

    @Nested
    inner class BadRequestResponse {

        @Test
        fun `400 with descriptive message를 반환한다`() {
            val response = badRequestResponse("Invalid input")

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "badRequestResponse should return 400 status"
            }
            val body = response.body as ErrorResponse
            assertEquals("Invalid input", body.error) {
                "Error body should include the provided message"
            }
        }
    }
}
