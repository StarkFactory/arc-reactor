package com.arc.reactor.controller

import com.arc.reactor.agent.budget.CostCalculator
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.server.ServerWebExchange

/**
 * ModelRegistryController 테스트.
 *
 * 모델 목록 조회, 가격표, 기본 모델 표시를 검증한다.
 */
class ModelRegistryControllerTest {

    private lateinit var costCalculatorProvider: ObjectProvider<CostCalculator>
    private lateinit var agentPropertiesProvider: ObjectProvider<AgentProperties>
    private lateinit var controller: ModelRegistryController

    @BeforeEach
    fun setup() {
        costCalculatorProvider = mockk()
        agentPropertiesProvider = mockk()
        every { agentPropertiesProvider.ifAvailable } returns null
        controller = ModelRegistryController(costCalculatorProvider, agentPropertiesProvider)
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

    @Nested
    inner class ListModels {

        @Test
        fun `관리자는 모델 목록을 조회할 수 있다`() {
            val result = controller.list(adminExchange())

            assertEquals(200, result.statusCode.value()) { "관리자 모델 조회는 200이어야 한다" }
            val body = result.body as List<*>
            assertTrue(body.isNotEmpty()) { "DEFAULT_PRICING에 등록된 모델이 있어야 한다" }
        }

        @Test
        fun `응답에 가격 정보가 포함된다`() {
            val result = controller.list(adminExchange())
            val body = result.body as List<*>
            val first = body.first() as ModelResponse

            assertTrue(first.inputPricePerMillionTokens >= 0) { "입력 가격은 0 이상이어야 한다" }
            assertTrue(first.outputPricePerMillionTokens >= 0) { "출력 가격은 0 이상이어야 한다" }
            assertTrue(first.name.isNotBlank()) { "모델 이름은 비어있지 않아야 한다" }
        }

        @Test
        fun `DEFAULT_PRICING의 모든 모델이 포함된다`() {
            val result = controller.list(adminExchange())
            val body = result.body as List<*>

            assertEquals(CostCalculator.DEFAULT_PRICING.size, body.size) {
                "DEFAULT_PRICING에 등록된 모델 수와 응답 수가 일치해야 한다"
            }
        }

        @Test
        fun `비관리자는 403을 받는다`() {
            val result = controller.list(userExchange())

            assertEquals(403, result.statusCode.value()) { "비관리자는 403이어야 한다" }
        }
    }
}
