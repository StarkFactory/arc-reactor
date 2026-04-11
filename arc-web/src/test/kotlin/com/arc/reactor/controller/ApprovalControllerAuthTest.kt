package com.arc.reactor.controller

import com.arc.reactor.approval.ApprovalStatus
import com.arc.reactor.approval.ApprovalSummary
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

@Tag("safety")
/**
 * ApprovalController 인증에 대한 테스트.
 *
 * 승인 컨트롤러의 인증/인가 동작을 검증합니다.
 */
class ApprovalControllerAuthTest {

    private val store = mockk<PendingApprovalStore>()
    private val adminAuditStore = mockk<AdminAuditStore>(relaxed = true)
    private val controller = ApprovalController(store, adminAuditStore)

    @Test
    fun `listPending은(는) rejects anonymous request`() = kotlinx.coroutines.test.runTest {
        val ex = exchange()

        val response = controller.listPending(offset = 0, limit = 50, exchange = ex)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "Anonymous request should be rejected when role is missing"
        }
        verify(exactly = 0) { store.listPending() }
        verify(exactly = 0) { store.listPendingByUser(any()) }
    }

    @Test
    fun `listPending은(는) returns all for admin`() = kotlinx.coroutines.test.runTest {
        every { store.listPending() } returns listOf(summary("ap-1", "user-1"))

        val response = controller.listPending(
            offset = 0, limit = 50,
            exchange = exchange(userId = "admin-1", role = UserRole.ADMIN)
        )
        @Suppress("UNCHECKED_CAST")
        val result = response.body as PaginatedResponse<AdminApprovalSummaryResponse>

        assertEquals(HttpStatus.OK, response.statusCode) { "Admin request should return 200" }
        assertEquals(1, result.items.size) { "Admin should receive all pending approvals" }
        assertEquals("ap-1", result.items.first().id) { "Mapped response should preserve approval id" }
        assertEquals("PENDING", result.items.first().status) { "Mapped response should expose status text" }
        verify(exactly = 1) { store.listPending() }
        verify(exactly = 0) { store.listPendingByUser(any()) }
    }

    @Test
    fun `listPending은(는) returns own requests for user`() = kotlinx.coroutines.test.runTest {
        every { store.listPendingByUser("user-1") } returns listOf(summary("ap-2", "user-1"))

        val response = controller.listPending(
            offset = 0, limit = 50,
            exchange = exchange(userId = "user-1", role = UserRole.USER)
        )
        @Suppress("UNCHECKED_CAST")
        val result = response.body as PaginatedResponse<AdminApprovalSummaryResponse>

        assertEquals(HttpStatus.OK, response.statusCode) { "User should be allowed to list own approvals" }
        assertEquals(1, result.items.size) { "User should receive only own pending approvals" }
        assertEquals("ap-2", result.items.first().id) { "User list should contain mapped approval entry" }
        verify(exactly = 1) { store.listPendingByUser("user-1") }
        verify(exactly = 0) { store.listPending() }
    }

    @Test
    fun `rejects non-admin를 승인한다`() {
        val response = controller.approve("ap-3", null, exchange(userId = "user-1", role = UserRole.USER))
        val body = response.body as ErrorResponse

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Non-admin approval action should be forbidden" }
        assertEquals("관리자 권한이 필요합니다", body.error) { "403 응답에 표준 관리자 접근 메시지가 포함되어야 한다" }
        verify(exactly = 0) { store.approve(any(), any()) }
    }

    @Test
    fun `allows admin를 승인한다`() {
        every { store.approve("ap-4", null) } returns true

        val response = controller.approve(
            "ap-4",
            null,
            exchange(userId = "admin-1", role = UserRole.ADMIN)
        )
        val body = response.body as ApprovalActionResponse

        assertEquals(HttpStatus.OK, response.statusCode) { "Admin approval action should succeed" }
        assertTrue(body.success) { "Approved action should return success=true" }
        verify(exactly = 1) { store.approve("ap-4", null) }
    }

    @Test
    fun `rejects non-admin를 거부한다`() {
        val response = controller.reject("ap-5", null, exchange(userId = "user-1", role = UserRole.USER))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Non-admin reject action should be forbidden" }
        verify(exactly = 0) { store.reject(any(), any()) }
    }

    private fun exchange(userId: String? = null, role: UserRole? = null): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attrs = mutableMapOf<String, Any>()
        if (userId != null) attrs[JwtAuthWebFilter.USER_ID_ATTRIBUTE] = userId
        if (role != null) attrs[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] = role
        every { exchange.attributes } returns attrs
        return exchange
    }

    private fun summary(id: String, userId: String) = ApprovalSummary(
        id = id,
        runId = "run-1",
        userId = userId,
        toolName = "tool-a",
        arguments = mapOf("k" to "v"),
        requestedAt = Instant.now(),
        status = ApprovalStatus.PENDING
    )
}
