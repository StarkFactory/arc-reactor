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
    inner class IsAnyAdmin {

        @Test
        fun `returns true when role is ADMIN_MANAGER`() {
            isAnyAdmin(exchangeWithRole(UserRole.ADMIN_MANAGER)) shouldBe true
        }

        @Test
        fun `returns true when role is ADMIN_DEVELOPER`() {
            isAnyAdmin(exchangeWithRole(UserRole.ADMIN_DEVELOPER)) shouldBe true
        }

        @Test
        fun `returns false when role is USER`() {
            isAnyAdmin(exchangeWithRole(UserRole.USER)) shouldBe false
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

    @Nested
    inner class NotFoundResponse {

        @Test
        fun `returns 404 with descriptive message`() {
            val response = notFoundResponse("Tenant not found: t1")
            response.statusCode shouldBe HttpStatus.NOT_FOUND
            val body = response.body as AdminErrorResponse
            body.error shouldBe "Tenant not found: t1"
        }
    }

    @Nested
    inner class ConflictResponse {

        @Test
        fun `returns 409 with descriptive message`() {
            val response = conflictResponse("Already exists")
            response.statusCode shouldBe HttpStatus.CONFLICT
            val body = response.body as AdminErrorResponse
            body.error shouldBe "Already exists"
        }
    }

    @Nested
    inner class BadRequestResponse {

        @Test
        fun `returns 400 with descriptive message`() {
            val response = badRequestResponse("Invalid input")
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            val body = response.body as AdminErrorResponse
            body.error shouldBe "Invalid input"
        }
    }
}
