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
    fun `get returns 403 for non-admin`() {
        val response = controller.get(userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `update returns 403 for non-admin`() {
        val response = controller.update(UpdateRagIngestionPolicyRequest(enabled = true), userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `delete returns 403 for non-admin`() {
        val response = controller.delete(userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `admin can update and audit is recorded`() {
        val response = controller.update(
            UpdateRagIngestionPolicyRequest(
                enabled = true,
                requireReview = true,
                allowedChannels = setOf("slack"),
                blockedPatterns = setOf("secret")
            ),
            adminExchange()
        )
        assertEquals(HttpStatus.OK, response.statusCode)
        val audits = adminAuditStore.list()
        assertEquals(1, audits.size)
        assertEquals("rag_ingestion_policy", audits.first().category)
        assertEquals("UPDATE", audits.first().action)
    }
}
