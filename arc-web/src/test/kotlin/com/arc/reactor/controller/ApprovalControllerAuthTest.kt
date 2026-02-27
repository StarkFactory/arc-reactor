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
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

class ApprovalControllerAuthTest {

    private val store = mockk<PendingApprovalStore>()
    private val controller = ApprovalController(store)

    @Test
    fun `listPending treats anonymous as admin when auth is disabled`() {
        val ex = exchange()
        every { store.listPending() } returns listOf(summary("ap-0", "user-0"))

        val result = controller.listPending(ex)

        assertEquals(1, result.size)
        verify(exactly = 1) { store.listPending() }
        verify(exactly = 0) { store.listPendingByUser(any()) }
    }

    @Test
    fun `listPending returns all for admin`() {
        every { store.listPending() } returns listOf(summary("ap-1", "user-1"))

        val result = controller.listPending(exchange(userId = "admin-1", role = UserRole.ADMIN))

        assertEquals(1, result.size)
        verify(exactly = 1) { store.listPending() }
        verify(exactly = 0) { store.listPendingByUser(any()) }
    }

    @Test
    fun `listPending returns own requests for user`() {
        every { store.listPendingByUser("user-1") } returns listOf(summary("ap-2", "user-1"))

        val result = controller.listPending(exchange(userId = "user-1", role = UserRole.USER))

        assertEquals(1, result.size)
        assertEquals("user-1", result.first().userId)
        verify(exactly = 1) { store.listPendingByUser("user-1") }
        verify(exactly = 0) { store.listPending() }
    }

    @Test
    fun `approve rejects non-admin`() {
        val thrown = assertThrows<ResponseStatusException> {
            controller.approve("ap-3", null, exchange(userId = "user-1", role = UserRole.USER))
        }

        assertEquals(HttpStatus.FORBIDDEN, thrown.statusCode)
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

        assertTrue(response.success)
        verify(exactly = 1) { store.approve("ap-4", null) }
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
