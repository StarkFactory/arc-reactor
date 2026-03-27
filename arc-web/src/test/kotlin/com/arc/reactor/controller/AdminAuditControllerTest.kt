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
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "비관리자 감사 로그 요청은 403이어야 한다"
        }
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

        assertEquals(HttpStatus.OK, response.statusCode) { "카테고리-액션 필터 요청은 200이어야 한다" }
        @Suppress("UNCHECKED_CAST")
        val body = response.body as PaginatedResponse<AdminAuditResponse>
        assertEquals(1, body.items.size) { "필터링된 감사 항목이 1개여야 한다" }
        assertEquals(1, body.total) { "필터링된 전체 개수가 1이어야 한다" }
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

        assertEquals(HttpStatus.OK, response.statusCode) {
            "관리자는 감사 로그 목록에 접근할 수 있어야 한다"
        }
        @Suppress("UNCHECKED_CAST")
        val paginated = response.body as PaginatedResponse<AdminAuditResponse>
        val rows = paginated.items
        assertEquals(1, rows.size) { "감사 로그가 정확히 1개여야 한다" }
        assertEquals(
            maskedAdminAccountRef(rawActor),
            rows.first().actor
        ) { "actor는 마스킹된 관리자 계정 식별자만 노출해야 한다" }
        assertTrue(!rows.first().actor.contains(rawActor)) {
            "actor에 원시 관리자 계정 식별자가 포함되지 않아야 한다"
        }
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

        assertEquals(HttpStatus.OK, response.statusCode) { "페이지 목록 요청은 200이어야 한다" }
        @Suppress("UNCHECKED_CAST")
        val body = response.body as PaginatedResponse<AdminAuditResponse>
        assertEquals(5, body.total) { "전체 항목 수가 매칭된 개수와 같아야 한다" }
        assertEquals(2, body.items.size) { "pageLimit만큼의 항목이 반환되어야 한다" }
        assertEquals(1, body.offset) { "오프셋이 요청값과 일치해야 한다" }
        assertEquals(2, body.limit) { "limit이 클램핑된 pageLimit과 일치해야 한다" }
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

        assertEquals(HttpStatus.OK, response.statusCode) { "200이 반환되어야 한다" }
        @Suppress("UNCHECKED_CAST")
        val body = response.body as PaginatedResponse<AdminAuditResponse>
        assertEquals(200, body.limit) { "limit이 200으로 클램핑되어야 한다" }
    }
}
