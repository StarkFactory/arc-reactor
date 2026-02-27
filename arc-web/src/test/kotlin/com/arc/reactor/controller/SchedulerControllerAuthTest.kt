package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.scheduler.DynamicSchedulerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class SchedulerControllerAuthTest {

    private val schedulerService = mockk<DynamicSchedulerService>()
    private val controller = SchedulerController(schedulerService)

    @Test
    fun `listJobs rejects non-admin`() {
        val response = controller.listJobs(exchange(userId = "user-1", role = UserRole.USER))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Non-admin list should be forbidden" }
        verify(exactly = 0) { schedulerService.list() }
    }

    @Test
    fun `getJob rejects non-admin`() {
        val response = controller.getJob("job-1", exchange(userId = "user-1", role = UserRole.USER))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Non-admin get should be forbidden" }
        verify(exactly = 0) { schedulerService.findById(any()) }
    }

    @Test
    fun `listJobs allows admin`() {
        every { schedulerService.list() } returns emptyList()

        val response = controller.listJobs(exchange(userId = "admin-1", role = UserRole.ADMIN))
        @Suppress("UNCHECKED_CAST")
        val result = response.body as List<ScheduledJobResponse>

        assertEquals(HttpStatus.OK, response.statusCode) { "Admin list should succeed" }
        assertEquals(0, result.size) { "Empty scheduler list should be returned as empty body list" }
        verify(exactly = 1) { schedulerService.list() }
    }

    private fun exchange(userId: String? = null, role: UserRole? = null): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attrs = mutableMapOf<String, Any>()
        if (userId != null) attrs[JwtAuthWebFilter.USER_ID_ATTRIBUTE] = userId
        if (role != null) attrs[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] = role
        every { exchange.attributes } returns attrs
        return exchange
    }
}
