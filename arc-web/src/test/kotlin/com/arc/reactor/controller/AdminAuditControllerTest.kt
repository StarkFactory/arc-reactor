package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditLog
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.auth.AdminAuthorizationSupport.maskedAdminAccountRef
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

/**
 * AdminAuditController에 대한 테스트.
 *
 * 관리자 감사 로그 REST API의 동작을 검증합니다.
 */
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
    fun `목록 returns 403 for non-admin`() {
        val store = InMemoryAdminAuditStore()
        val controller = AdminAuditController(store)
        val response = controller.list(
            limit = 100, category = null, action = null,
            offset = 0, pageLimit = 50, exchange = userExchange()
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `목록 supports category-action filters`() {
        val store = InMemoryAdminAuditStore()
        store.save(AdminAuditLog(category = "tool_policy", action = "UPDATE", actor = "admin"))
        store.save(AdminAuditLog(category = "mcp_server", action = "CREATE", actor = "admin"))
        store.save(AdminAuditLog(category = "mcp_server", action = "DELETE", actor = "admin"))
        val controller = AdminAuditController(store)

        val response = controller.list(
            limit = 10, category = "mcp_server", action = "delete",
            offset = 0, pageLimit = 50, exchange = adminExchange()
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        @Suppress("UNCHECKED_CAST")
        val body = response.body as PaginatedResponse<AdminAuditResponse>
        assertEquals(1, body.items.size, "Should have 1 filtered audit entry")
        assertEquals(1, body.total, "Total should match filtered count")
    }

    @Test
    fun `목록 exposes admin account reference without profile fields`() {
        val store = InMemoryAdminAuditStore()
        val rawActor = "80b18ee9-d20d-4359-bc5a-a40c4754f958"
        store.save(
            AdminAuditLog(
                category = "mcp_server",
                action = "UPDATE",
                actor = rawActor
            )
        )
        val controller = AdminAuditController(store)

        val response = controller.list(
            limit = 10, category = null, action = null,
            offset = 0, pageLimit = 50, exchange = adminExchange()
        )

        assertEquals(HttpStatus.OK, response.statusCode, "Admin audit list should be accessible for admins")
        @Suppress("UNCHECKED_CAST")
        val paginated = response.body as PaginatedResponse<AdminAuditResponse>
        val rows = paginated.items
        assertEquals(1, rows.size, "Expected exactly one audit row")
        assertEquals(
            maskedAdminAccountRef(rawActor),
            rows.first().actor,
            "Actor should expose only masked admin account identifier"
        )
        assertTrue(
            !rows.first().actor.contains(rawActor),
            "Actor should not expose raw admin account identifier"
        )
    }

    @Test
    fun `목록 paginates results correctly`() {
        val store = InMemoryAdminAuditStore()
        repeat(5) { i ->
            store.save(AdminAuditLog(category = "test", action = "ACTION$i", actor = "admin"))
        }
        val controller = AdminAuditController(store)

        val response = controller.list(
            limit = null, category = "test", action = null,
            offset = 1, pageLimit = 2, exchange = adminExchange()
        )

        assertEquals(HttpStatus.OK, response.statusCode, "Paginated list should return 200")
        @Suppress("UNCHECKED_CAST")
        val body = response.body as PaginatedResponse<AdminAuditResponse>
        assertEquals(5, body.total, "Total should be all matching entries")
        assertEquals(2, body.items.size, "Should return pageLimit items")
        assertEquals(1, body.offset, "Offset should match request")
        assertEquals(2, body.limit, "Limit should match clamped pageLimit")
    }

    @Test
    fun `목록 clamps limit to 200`() {
        val store = InMemoryAdminAuditStore()
        store.save(AdminAuditLog(category = "test", action = "A", actor = "admin"))
        val controller = AdminAuditController(store)

        val response = controller.list(
            limit = null, category = null, action = null,
            offset = 0, pageLimit = 999, exchange = adminExchange()
        )

        assertEquals(HttpStatus.OK, response.statusCode, "Should return 200")
        @Suppress("UNCHECKED_CAST")
        val body = response.body as PaginatedResponse<AdminAuditResponse>
        assertEquals(200, body.limit, "Limit should be clamped to 200")
    }
}
