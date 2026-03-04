package com.arc.reactor.admin.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class AdminAuthHelperTest {

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
            isAdmin(exchangeWithRole(UserRole.ADMIN)) shouldBe true
        }

        @Test
        fun `returns true when role is ADMIN_DEVELOPER`() {
            isAdmin(exchangeWithRole(UserRole.ADMIN_DEVELOPER)) shouldBe true
        }

        @Test
        fun `returns false when role is ADMIN_MANAGER`() {
            isAdmin(exchangeWithRole(UserRole.ADMIN_MANAGER)) shouldBe false
        }

        @Test
        fun `returns false when role is USER`() {
            isAdmin(exchangeWithRole(UserRole.USER)) shouldBe false
        }

        @Test
        fun `returns false when role is null`() {
            isAdmin(exchangeWithRole(null)) shouldBe false
        }
    }

    @Nested
    inner class CurrentActor {

        @Test
        fun `returns user id when present`() {
            val exchange = mockk<ServerWebExchange>()
            every { exchange.attributes } returns mutableMapOf(
                JwtAuthWebFilter.USER_ID_ATTRIBUTE to "admin-1"
            )

            currentActor(exchange) shouldBe "admin-1"
        }

        @Test
        fun `returns anonymous when user id missing`() {
            val exchange = mockk<ServerWebExchange>()
            every { exchange.attributes } returns mutableMapOf()

            currentActor(exchange) shouldBe "anonymous"
        }
    }

    @Nested
    inner class ForbiddenResponse {

        @Test
        fun `returns 403 with error body`() {
            val response = forbiddenResponse()
            response.statusCode shouldBe HttpStatus.FORBIDDEN
            val body = response.body as AdminErrorResponse
            body.error shouldContain "Admin access required"
        }
    }
}
