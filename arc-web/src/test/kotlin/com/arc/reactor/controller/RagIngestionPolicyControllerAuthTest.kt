package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.RagIngestionDynamicProperties
import com.arc.reactor.agent.config.RagIngestionProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.rag.ingestion.InMemoryRagIngestionPolicyStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicy
import com.arc.reactor.rag.ingestion.RagIngestionPolicyProvider
import com.arc.reactor.rag.ingestion.RagIngestionPolicyStore
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

/**
 * RagIngestionPolicyController 인증에 대한 테스트.
 *
 * RAG 인제스트 정책 컨트롤러의 인증/인가를 검증합니다.
 */
class RagIngestionPolicyControllerAuthTest {

    private lateinit var properties: AgentProperties
    private lateinit var store: RagIngestionPolicyStore
    private lateinit var provider: RagIngestionPolicyProvider
    private lateinit var adminAuditStore: AdminAuditStore
    private lateinit var controller: RagIngestionPolicyController

    @BeforeEach
    fun setup() {
        properties = AgentProperties(
            rag = RagProperties(
                ingestion = RagIngestionProperties(
                    enabled = true,
                    dynamic = RagIngestionDynamicProperties(enabled = true)
                )
            )
        )
        store = InMemoryRagIngestionPolicyStore(
            RagIngestionPolicy.fromProperties(properties.rag.ingestion)
        )
        provider = RagIngestionPolicyProvider(properties.rag.ingestion, store)
        adminAuditStore = InMemoryAdminAuditStore()
        controller = RagIngestionPolicyController(
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
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "비관리자 RAG 정책 조회 요청은 403이어야 한다" }
    }

    @Test
    fun `returns 403 for non-admin를 업데이트한다`() {
        val response = controller.update(UpdateRagIngestionPolicyRequest(enabled = true), userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "비관리자 RAG 정책 업데이트 요청은 403이어야 한다" }
    }

    @Test
    fun `returns 403 for non-admin를 삭제한다`() {
        val response = controller.delete(userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "비관리자 RAG 정책 삭제 요청은 403이어야 한다" }
    }

    @Test
    fun `admin can update and audit은(는) recorded이다`() {
        val response = controller.update(
            UpdateRagIngestionPolicyRequest(
                enabled = true,
                requireReview = true,
                allowedChannels = setOf("slack"),
                blockedPatterns = setOf("secret")
            ),
            adminExchange()
        )
        assertEquals(HttpStatus.OK, response.statusCode) { "관리자 RAG 정책 업데이트 요청은 200이어야 한다" }
        val audits = adminAuditStore.list()
        assertEquals(1, audits.size) { "감사 로그가 1건 기록되어야 한다" }
        assertEquals("rag_ingestion_policy", audits.first().category) { "감사 카테고리가 rag_ingestion_policy여야 한다" }
        assertEquals("UPDATE", audits.first().action) { "감사 액션이 UPDATE여야 한다" }
    }
}
