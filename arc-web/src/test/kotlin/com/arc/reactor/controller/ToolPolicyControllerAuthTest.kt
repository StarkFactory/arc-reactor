package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ToolPolicyDynamicProperties
import com.arc.reactor.agent.config.ToolPolicyProperties
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

class ToolPolicyControllerAuthTest {

    private lateinit var properties: AgentProperties
    private lateinit var store: ToolPolicyStore
    private lateinit var provider: ToolPolicyProvider
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
        controller = ToolPolicyController(
            properties = properties,
            store = store,
            provider = provider
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
        val response = controller.update(UpdateToolPolicyRequest(enabled = true), userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `delete returns 403 for non-admin`() {
        val response = controller.delete(userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `admin can update`() {
        val response = controller.update(
            UpdateToolPolicyRequest(
                enabled = true,
                writeToolNames = setOf("jira_create_issue"),
                denyWriteChannels = setOf("slack"),
                denyWriteMessage = "blocked"
            ),
            adminExchange()
        )
        assertEquals(HttpStatus.OK, response.statusCode)
    }
}
