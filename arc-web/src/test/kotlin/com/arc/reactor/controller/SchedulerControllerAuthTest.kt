package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.scheduler.DynamicSchedulerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

class SchedulerControllerAuthTest {

    private val schedulerService = mockk<DynamicSchedulerService>()
    private val controller = SchedulerController(schedulerService)

    @Test
    fun `listJobs rejects non-admin`() {
        val thrown = assertThrows<ResponseStatusException> {
            controller.listJobs(exchange(userId = "user-1", role = UserRole.USER))
        }

        assertEquals(HttpStatus.FORBIDDEN, thrown.statusCode)
        verify(exactly = 0) { schedulerService.list() }
    }

    @Test
    fun `getJob rejects non-admin`() {
        val thrown = assertThrows<ResponseStatusException> {
            controller.getJob("job-1", exchange(userId = "user-1", role = UserRole.USER))
        }

        assertEquals(HttpStatus.FORBIDDEN, thrown.statusCode)
        verify(exactly = 0) { schedulerService.findById(any()) }
    }

    @Test
    fun `listJobs allows admin`() {
        every { schedulerService.list() } returns emptyList()

        val result = controller.listJobs(exchange(userId = "admin-1", role = UserRole.ADMIN))

        assertEquals(0, result.size)
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
