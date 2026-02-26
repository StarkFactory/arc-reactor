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
        fun `returns false when role is USER`() {
            isAdmin(exchangeWithRole(UserRole.USER)) shouldBe false
        }

        @Test
        fun `returns true when role is null (auth disabled)`() {
            isAdmin(exchangeWithRole(null)) shouldBe true
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
