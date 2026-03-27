package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.intent.IntentRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

/**
 * IntentController 인증에 대한 테스트.
 *
 * 인텐트 컨트롤러의 인증/인가 동작을 검증합니다.
 */
class IntentControllerAuthTest {

    private lateinit var registry: IntentRegistry
    private lateinit var controller: IntentController

    @BeforeEach
    fun setup() {
        registry = mockk(relaxed = true)
        controller = IntentController(intentRegistry = registry)
    }

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN
        )
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER
        )
        return exchange
    }

    @Test
    fun `목록 returns 403 for non-admin`() {
        val response = controller.listIntents(userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "비관리자 인텐트 목록 요청은 403이어야 한다"
        }
    }

    @Test
    fun `returns 403 for non-admin를 가져온다`() {
        val response = controller.getIntent("anything", userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "비관리자 인텐트 조회 요청은 403이어야 한다"
        }
    }

    @Test
    fun `목록 allows admin`() {
        val response = controller.listIntents(adminExchange())
        assertEquals(HttpStatus.OK, response.statusCode) { "관리자 인텐트 목록 요청은 200이어야 한다" }
    }
}

