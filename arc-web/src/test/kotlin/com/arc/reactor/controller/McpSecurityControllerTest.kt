package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.McpConfigProperties
import com.arc.reactor.agent.config.McpSecurityProperties
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.mcp.InMemoryMcpSecurityPolicyStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpSecurityConfig
import com.arc.reactor.mcp.McpSecurityPolicyProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class McpSecurityControllerTest {

    private lateinit var store: InMemoryMcpSecurityPolicyStore
    private lateinit var provider: McpSecurityPolicyProvider
    private lateinit var manager: McpManager
    private lateinit var auditStore: InMemoryAdminAuditStore
    private lateinit var controller: McpSecurityController

    private fun adminExchange(): ServerWebExchange = exchangeFor(UserRole.ADMIN)

    private fun userExchange(): ServerWebExchange = exchangeFor(UserRole.USER)

    private fun exchangeFor(role: UserRole): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>(relaxed = true)
        val attrs = mutableMapOf<String, Any>(JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to role)
        io.mockk.every { exchange.attributes } returns attrs
        return exchange
    }

    @BeforeEach
    fun setUp() {
        store = InMemoryMcpSecurityPolicyStore()
        provider = McpSecurityPolicyProvider(
            defaultConfig = McpSecurityConfig(
                allowedServerNames = setOf("atlassian"),
                maxToolOutputLength = 50_000
            ),
            store = store
        )
        manager = mockk(relaxed = true)
        auditStore = InMemoryAdminAuditStore()
        controller = McpSecurityController(
            properties = AgentProperties(
                mcp = McpConfigProperties(
                    security = McpSecurityProperties(
                        allowedServerNames = setOf("atlassian"),
                        maxToolOutputLength = 50_000
                    )
                )
            ),
            store = store,
            provider = provider,
            mcpManager = manager,
            adminAuditStore = auditStore
        )
    }

    @Test
    fun `get은(는) expose effective and config default policy해야 한다`() {
        val response = controller.get(adminExchange())

        assertEquals(HttpStatus.OK, response.statusCode) { "Admin get should return 200 OK" }
        val body = assertInstanceOf(McpSecurityPolicyStateResponse::class.java, response.body)
        assertEquals(setOf("atlassian"), body.effective.allowedServerNames) {
            "Effective policy should fall back to config defaults when no stored override exists"
        }
        assertNull(body.stored) { "Stored policy should be null before any admin update" }
    }

    @Test
    fun `update은(는) persist policy and reapply runtime security해야 한다`() {
        val response = controller.update(
            request = UpdateMcpSecurityPolicyRequest(
                allowedServerNames = setOf("atlassian", "swagger-petstore"),
                maxToolOutputLength = 120_000
            ),
            exchange = adminExchange()
        )

        assertEquals(HttpStatus.OK, response.statusCode) { "Update should return 200 OK" }
        val body = assertInstanceOf(McpSecurityPolicyResponse::class.java, response.body)
        assertEquals(setOf("atlassian", "swagger-petstore"), body.allowedServerNames) {
            "Stored allowlist should include the new Swagger MCP server"
        }
        assertEquals(120_000, body.maxToolOutputLength) {
            "Stored maxToolOutputLength should reflect the admin override"
        }
        assertEquals(setOf("atlassian", "swagger-petstore"), provider.currentPolicy().allowedServerNames) {
            "Provider should immediately serve the updated security policy"
        }
        verify(exactly = 1) { manager.reapplySecurityPolicy() }
        assertEquals(1, auditStore.list().size) { "Policy update should emit an admin audit record" }
    }

    @Test
    fun `delete은(는) reset to config defaults and reapply runtime security해야 한다`() {
        controller.update(
            request = UpdateMcpSecurityPolicyRequest(
                allowedServerNames = setOf("swagger-petstore"),
                maxToolOutputLength = 150_000
            ),
            exchange = adminExchange()
        )

        val response = controller.delete(adminExchange())

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "Delete should return 204 NO_CONTENT" }
        assertNull(store.getOrNull()) { "Stored override should be removed after delete" }
        assertEquals(setOf("atlassian"), provider.currentPolicy().allowedServerNames) {
            "Provider should fall back to config defaults after delete"
        }
        verify(exactly = 2) { manager.reapplySecurityPolicy() }
        assertEquals(2, auditStore.list().size) { "Update + delete should both be audited" }
    }

    @Test
    fun `non admin requests은(는) be rejected해야 한다`() {
        val response = controller.get(userExchange())

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "Non-admin callers should not be able to view MCP security policy"
        }
    }
}
