package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditLog
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `list redacts actor identifiers`() {
        val store = InMemoryAdminAuditStore()
        store.save(
            AdminAuditLog(
                category = "mcp_server",
                action = "UPDATE",
                actor = "80b18ee9-d20d-4359-bc5a-a40c4754f958"
            )
        )
        val controller = AdminAuditController(store)

        val response = controller.list(limit = 10, category = null, action = null, exchange = adminExchange())

        assertEquals(HttpStatus.OK, response.statusCode, "Admin audit list should be accessible for admins")
        @Suppress("UNCHECKED_CAST")
        val rows = response.body as List<AdminAuditResponse>
        assertEquals(1, rows.size, "Expected exactly one audit row")
        assertEquals("admin", rows.first().actor, "Actor should be anonymized in API responses")
        assertTrue(
            !rows.first().actor.contains("80b18ee9"),
            "Raw actor identifiers must not leak in API responses"
        )
    }
}
