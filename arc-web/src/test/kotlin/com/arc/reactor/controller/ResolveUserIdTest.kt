package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

/**
 * resolveUserId에 대한 안전성 테스트.
 *
 * JWT 인증 없이 클라이언트가 제공한 userId를 그대로 사용하면
 * userId 스푸핑으로 타 사용자 Rate Limit 소진, 세션 탈취가 가능하다.
 * 이 함수가 스푸핑을 올바르게 차단하는지 검증한다.
 */
class ResolveUserIdTest {

    private fun exchange(jwtUserId: String? = null): MockServerWebExchange {
        val request = MockServerHttpRequest.get("/api/chat").build()
        val ex = MockServerWebExchange.from(request)
        if (jwtUserId != null) {
            ex.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] = jwtUserId
        }
        return ex
    }

    @Nested
    inner class JwtAuthenticated {

        @Test
        fun `JWT userId가 있으면 해당 값을 사용한다`() {
            val result = resolveUserId(exchange(jwtUserId = "jwt-user-42"), requestUserId = null)

            result shouldBe "jwt-user-42"
        }

        @Test
        fun `JWT userId가 있으면 requestUserId를 무시한다`() {
            val result = resolveUserId(
                exchange(jwtUserId = "jwt-user-42"),
                requestUserId = "spoofed-admin"
            )

            result shouldBe "jwt-user-42"
        }
    }

    @Nested
    inner class NoJwtAuthentication {

        @Test
        fun `JWT 없이 requestUserId가 있으면 스푸핑 방지로 anonymous를 반환한다`() {
            val result = resolveUserId(exchange(), requestUserId = "attacker-user")

            result shouldBe "anonymous"
        }

        @Test
        fun `JWT 없이 requestUserId가 null이면 anonymous를 반환한다`() {
            val result = resolveUserId(exchange(), requestUserId = null)

            result shouldBe "anonymous"
        }

        @Test
        fun `JWT 없이 requestUserId가 빈 문자열이면 anonymous를 반환한다`() {
            val result = resolveUserId(exchange(), requestUserId = "")

            result shouldBe "anonymous"
        }

        @Test
        fun `JWT 없이 requestUserId가 공백만이면 anonymous를 반환한다`() {
            val result = resolveUserId(exchange(), requestUserId = "   ")

            result shouldBe "anonymous"
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `JWT attribute가 String이 아닌 타입이면 anonymous로 폴백한다`() {
            val ex = exchange()
            ex.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] = 12345 // Int, not String

            val result = resolveUserId(ex, requestUserId = "spoofed")

            result shouldBe "anonymous"
        }
    }
}
