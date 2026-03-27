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

/** 관리자 인증 지원 함수(isAdmin, isAnyAdmin, forbiddenResponse 등)의 동작 테스트 */
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
            isAdmin(exchangeWithRole(UserRole.ADMIN)) shouldBe true
        }

        @Test
        fun `role is ADMIN_DEVELOPER일 때 true를 반환한다`() {
            isAdmin(exchangeWithRole(UserRole.ADMIN_DEVELOPER)) shouldBe true
        }

        @Test
        fun `role is ADMIN_MANAGER일 때 false를 반환한다`() {
            isAdmin(exchangeWithRole(UserRole.ADMIN_MANAGER)) shouldBe false
        }

        @Test
        fun `role is USER일 때 false를 반환한다`() {
            isAdmin(exchangeWithRole(UserRole.USER)) shouldBe false
        }

        @Test
        fun `role is null일 때 false를 반환한다`() {
            isAdmin(exchangeWithRole(null)) shouldBe false
        }
    }

    @Nested
    inner class IsAnyAdmin {

        @Test
        fun `role is ADMIN_MANAGER일 때 true를 반환한다`() {
            isAnyAdmin(exchangeWithRole(UserRole.ADMIN_MANAGER)) shouldBe true
        }

        @Test
        fun `role is ADMIN_DEVELOPER일 때 true를 반환한다`() {
            isAnyAdmin(exchangeWithRole(UserRole.ADMIN_DEVELOPER)) shouldBe true
        }

        @Test
        fun `role is USER일 때 false를 반환한다`() {
            isAnyAdmin(exchangeWithRole(UserRole.USER)) shouldBe false
        }
    }

    @Nested
    inner class CurrentActor {

        @Test
        fun `present일 때 user id를 반환한다`() {
            val exchange = mockk<ServerWebExchange>()
            every { exchange.attributes } returns mutableMapOf(
                JwtAuthWebFilter.USER_ID_ATTRIBUTE to "admin-1"
            )

            currentActor(exchange) shouldBe "admin-1"
        }

        @Test
        fun `user id missing일 때 anonymous를 반환한다`() {
            val exchange = mockk<ServerWebExchange>()
            every { exchange.attributes } returns mutableMapOf()

            currentActor(exchange) shouldBe "anonymous"
        }
    }

    @Nested
    inner class ForbiddenResponse {

        @Test
        fun `403 with error body를 반환한다`() {
            val response = forbiddenResponse()
            response.statusCode shouldBe HttpStatus.FORBIDDEN
            val body = response.body as AdminErrorResponse
            body.error shouldContain "Admin access required"
        }
    }

    @Nested
    inner class NotFoundResponse {

        @Test
        fun `404 with descriptive message를 반환한다`() {
            val response = notFoundResponse("Tenant not found: t1")
            response.statusCode shouldBe HttpStatus.NOT_FOUND
            val body = response.body as AdminErrorResponse
            body.error shouldBe "Tenant not found: t1"
        }
    }

    @Nested
    inner class ConflictResponse {

        @Test
        fun `409 with descriptive message를 반환한다`() {
            val response = conflictResponse("Already exists")
            response.statusCode shouldBe HttpStatus.CONFLICT
            val body = response.body as AdminErrorResponse
            body.error shouldBe "Already exists"
        }
    }

    @Nested
    inner class BadRequestResponse {

        @Test
        fun `400 with descriptive message를 반환한다`() {
            val response = badRequestResponse("Invalid input")
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            val body = response.body as AdminErrorResponse
            body.error shouldBe "Invalid input"
        }
    }
}
