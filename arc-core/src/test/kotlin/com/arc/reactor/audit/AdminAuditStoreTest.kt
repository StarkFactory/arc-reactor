package com.arc.reactor.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdminAuditStoreTest {

    @Test
    fun `in-memory store filters by category and action`() {
        val store = InMemoryAdminAuditStore()
        store.save(AdminAuditLog(category = "tool_policy", action = "UPDATE", actor = "admin-1"))
        store.save(AdminAuditLog(category = "mcp_server", action = "CREATE", actor = "admin-2"))
        store.save(AdminAuditLog(category = "mcp_server", action = "DELETE", actor = "admin-2"))

        val mcpOnly = store.list(limit = 10, category = "mcp_server")
        assertEquals(2, mcpOnly.size)

        val mcpDelete = store.list(limit = 10, category = "mcp_server", action = "delete")
        assertEquals(1, mcpDelete.size)
        assertEquals("DELETE", mcpDelete.first().action)
    }
}
