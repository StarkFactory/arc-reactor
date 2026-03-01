package com.arc.reactor.controller

import com.arc.reactor.approval.ApprovalStatus
import com.arc.reactor.approval.ApprovalSummary
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

class ApprovalControllerAuthTest {

    private val store = mockk<PendingApprovalStore>()
    private val controller = ApprovalController(store)

    @Test
    fun `listPending rejects anonymous request`() {
        val ex = exchange()

        val response = controller.listPending(ex)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "Anonymous request should be rejected when role is missing"
        }
        verify(exactly = 0) { store.listPending() }
        verify(exactly = 0) { store.listPendingByUser(any()) }
    }

    @Test
    fun `listPending returns all for admin`() {
        every { store.listPending() } returns listOf(summary("ap-1", "user-1"))

        val response = controller.listPending(exchange(userId = "admin-1", role = UserRole.ADMIN))
        @Suppress("UNCHECKED_CAST")
        val result = response.body as List<ApprovalSummary>

        assertEquals(HttpStatus.OK, response.statusCode) { "Admin request should return 200" }
        assertEquals(1, result.size) { "Admin should receive all pending approvals" }
        verify(exactly = 1) { store.listPending() }
        verify(exactly = 0) { store.listPendingByUser(any()) }
    }

    @Test
    fun `listPending returns own requests for user`() {
        every { store.listPendingByUser("user-1") } returns listOf(summary("ap-2", "user-1"))

        val response = controller.listPending(exchange(userId = "user-1", role = UserRole.USER))
        @Suppress("UNCHECKED_CAST")
        val result = response.body as List<ApprovalSummary>

        assertEquals(HttpStatus.OK, response.statusCode) { "User should be allowed to list own approvals" }
        assertEquals(1, result.size) { "User should receive only own pending approvals" }
        assertEquals("user-1", result.first().userId) { "Listed approval should belong to requesting user" }
        verify(exactly = 1) { store.listPendingByUser("user-1") }
        verify(exactly = 0) { store.listPending() }
    }

    @Test
    fun `approve rejects non-admin`() {
        val response = controller.approve("ap-3", null, exchange(userId = "user-1", role = UserRole.USER))
        val body = response.body as ErrorResponse

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Non-admin approval action should be forbidden" }
        assertEquals("Admin access required", body.error) { "Forbidden responses should use standard admin message" }
        verify(exactly = 0) { store.approve(any(), any()) }
    }

    @Test
    fun `approve allows admin`() {
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
    fun `reject rejects non-admin`() {
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
