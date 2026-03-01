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
        fun `returns true when role is ADMIN`() {
            assertTrue(isAdmin(exchangeWithRole(UserRole.ADMIN))) {
                "ADMIN role should be treated as admin"
            }
        }

        @Test
        fun `returns false when role is USER`() {
            assertFalse(isAdmin(exchangeWithRole(UserRole.USER))) {
                "USER role should not be treated as admin"
            }
        }

        @Test
        fun `returns false when role is null`() {
            assertFalse(isAdmin(exchangeWithRole(null))) {
                "Null role should fail-close as non-admin"
            }
        }
    }

    @Nested
    inner class ForbiddenResponse {

        @Test
        fun `returns 403 with standard error body`() {
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
}
