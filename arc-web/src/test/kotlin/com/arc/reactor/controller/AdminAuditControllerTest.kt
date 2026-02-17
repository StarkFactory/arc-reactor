package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditLog
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class AdminAuditControllerTest {

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
    fun `list returns 403 for non-admin`() {
        val store = InMemoryAdminAuditStore()
        val controller = AdminAuditController(store)
        val response = controller.list(limit = 100, category = null, action = null, exchange = userExchange())
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `list supports category-action filters`() {
        val store = InMemoryAdminAuditStore()
        store.save(AdminAuditLog(category = "tool_policy", action = "UPDATE", actor = "admin"))
        store.save(AdminAuditLog(category = "mcp_server", action = "CREATE", actor = "admin"))
        store.save(AdminAuditLog(category = "mcp_server", action = "DELETE", actor = "admin"))
        val controller = AdminAuditController(store)

        val response = controller.list(limit = 10, category = "mcp_server", action = "delete", exchange = adminExchange())

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body as List<*>
        assertEquals(1, body.size)
    }
}
