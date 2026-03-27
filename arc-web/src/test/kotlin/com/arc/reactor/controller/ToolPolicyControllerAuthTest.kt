package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ToolPolicyDynamicProperties
import com.arc.reactor.agent.config.ToolPolicyProperties
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.policy.tool.InMemoryToolPolicyStore
import com.arc.reactor.policy.tool.ToolPolicy
import com.arc.reactor.policy.tool.ToolPolicyProvider
import com.arc.reactor.policy.tool.ToolPolicyStore
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

/**
 * ToolPolicyController 인증에 대한 테스트.
 *
 * 도구 정책 컨트롤러의 인증/인가를 검증합니다.
 */
class ToolPolicyControllerAuthTest {

    private lateinit var properties: AgentProperties
    private lateinit var store: ToolPolicyStore
    private lateinit var provider: ToolPolicyProvider
    private lateinit var adminAuditStore: AdminAuditStore
    private lateinit var controller: ToolPolicyController

    @BeforeEach
    fun setup() {
        properties = AgentProperties(
            toolPolicy = ToolPolicyProperties(
                enabled = true,
                dynamic = ToolPolicyDynamicProperties(enabled = true)
            )
        )
        store = InMemoryToolPolicyStore(initial = ToolPolicy.fromProperties(properties.toolPolicy))
        provider = ToolPolicyProvider(properties = properties.toolPolicy, store = store)
        adminAuditStore = InMemoryAdminAuditStore()
        controller = ToolPolicyController(
            properties = properties,
            store = store,
            provider = provider,
            adminAuditStore = adminAuditStore
        )
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
    fun `returns 403 for non-admin를 가져온다`() {
        val response = controller.get(userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "비관리자 도구 정책 조회 요청은 403이어야 한다"
        }
    }

    @Test
    fun `returns 403 for non-admin를 업데이트한다`() {
        val response = controller.update(UpdateToolPolicyRequest(enabled = true), userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "비관리자 도구 정책 업데이트 요청은 403이어야 한다"
        }
    }

    @Test
    fun `returns 403 for non-admin를 삭제한다`() {
        val response = controller.delete(userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "비관리자 도구 정책 삭제 요청은 403이어야 한다"
        }
    }

    @Test
    fun `admin은(는) can update`() {
        val response = controller.update(
            UpdateToolPolicyRequest(
                enabled = true,
                writeToolNames = setOf("jira_create_issue"),
                denyWriteChannels = setOf("slack"),
                denyWriteMessage = "blocked"
            ),
            adminExchange()
        )
        assertEquals(HttpStatus.OK, response.statusCode) {
            "관리자 도구 정책 업데이트 요청은 200이어야 한다"
        }
        val audits = adminAuditStore.list()
        assertEquals(1, audits.size) { "감사 로그가 1건 기록되어야 한다" }
        assertEquals("tool_policy", audits.first().category) { "감사 카테고리가 tool_policy여야 한다" }
        assertEquals("UPDATE", audits.first().action) { "감사 액션이 UPDATE여야 한다" }
    }
}
