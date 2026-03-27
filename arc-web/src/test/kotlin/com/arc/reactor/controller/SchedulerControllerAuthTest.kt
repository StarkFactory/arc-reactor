package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.JobExecutionStatus
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

@Tag("safety")
/**
 * SchedulerController 인증에 대한 테스트.
 *
 * 스케줄러 컨트롤러의 인증/인가 동작을 검증합니다.
 */
class SchedulerControllerAuthTest {

    private val schedulerService = mockk<DynamicSchedulerService>()
    private val controller = SchedulerController(schedulerService)

    @Test
    fun `listJobs은(는) rejects non-admin`() {
        val response = controller.listJobs(
            offset = 0, limit = 50,
            exchange = exchange(userId = "user-1", role = UserRole.USER)
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Non-admin list should be forbidden" }
        verify(exactly = 0) { schedulerService.list() }
    }

    @Test
    fun `getJob은(는) rejects non-admin`() {
        val response = controller.getJob("job-1", exchange(userId = "user-1", role = UserRole.USER))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Non-admin get should be forbidden" }
        verify(exactly = 0) { schedulerService.findById(any()) }
    }

    @Test
    fun `listJobs은(는) rejects ADMIN_MANAGER`() {
        val response = controller.listJobs(
            offset = 0, limit = 50,
            exchange = exchange(userId = "manager-1", role = UserRole.ADMIN_MANAGER)
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "Manager-scope admin should be forbidden from developer scheduler controls"
        }
        verify(exactly = 0) { schedulerService.list() }
    }

    @Test
    fun `listJobs은(는) allows admin`() {
        every { schedulerService.list() } returns emptyList()

        val response = controller.listJobs(
            offset = 0, limit = 50,
            exchange = exchange(userId = "admin-1", role = UserRole.ADMIN)
        )
        @Suppress("UNCHECKED_CAST")
        val result = response.body as PaginatedResponse<ScheduledJobResponse>

        assertEquals(HttpStatus.OK, response.statusCode) { "Admin list should succeed" }
        assertEquals(0, result.items.size) { "Empty scheduler list should be returned as empty items" }
        verify(exactly = 1) { schedulerService.list() }
    }

    @Test
    fun `listJobs은(는) allows ADMIN_DEVELOPER`() {
        every { schedulerService.list() } returns emptyList()

        val response = controller.listJobs(
            offset = 0, limit = 50,
            exchange = exchange(userId = "dev-admin-1", role = UserRole.ADMIN_DEVELOPER)
        )
        @Suppress("UNCHECKED_CAST")
        val result = response.body as PaginatedResponse<ScheduledJobResponse>

        assertEquals(HttpStatus.OK, response.statusCode) { "Developer-scope admin list should succeed" }
        assertEquals(0, result.items.size) { "Scheduler list should be empty when service returns empty list" }
        verify(exactly = 1) { schedulerService.list() }
    }

    @Test
    fun `listJobs은(는) exposes latest failure reason and result preview`() {
        every { schedulerService.list() } returns listOf(
            ScheduledJob(
                id = "job-1",
                name = "Release digest",
                cronExpression = "0 0 9 * * *",
                jobType = ScheduledJobType.AGENT,
                agentPrompt = "Summarize release risk",
                lastStatus = JobExecutionStatus.FAILED,
                lastResult = "Job 'Release digest' failed: MCP server 'atlassian' is not connected"
            )
        )

        val response = controller.listJobs(
            offset = 0, limit = 50,
            exchange = exchange(userId = "admin-1", role = UserRole.ADMIN)
        )
        @Suppress("UNCHECKED_CAST")
        val result = response.body as PaginatedResponse<ScheduledJobResponse>

        assertEquals(HttpStatus.OK, response.statusCode) { "잡 목록 응답이 200이어야 한다" }
        assertEquals(1, result.items.size) { "잡이 1개 반환되어야 한다" }
        assertEquals(
            "MCP server 'atlassian' is not connected",
            result.items[0].lastFailureReason
        ) { "lastFailureReason이 일치해야 한다" }
        assertEquals(
            "Job 'Release digest' failed: MCP server 'atlassian' is not connected",
            result.items[0].lastResultPreview
        ) { "lastResultPreview가 일치해야 한다" }
        assertNull(result.items[0].lastRunAt) { "lastRunAt이 null이어야 한다" }
    }

    @Test
    fun `triggerJob은(는) rejects non-admin`() {
        val response = controller.triggerJob("job-1", exchange(userId = "user-1", role = UserRole.USER)).block()

        assertEquals(HttpStatus.FORBIDDEN, response?.statusCode) { "Non-admin trigger should be forbidden" }
        verify(exactly = 0) { schedulerService.trigger(any()) }
    }

    @Test
    fun `triggerJob은(는) allows admin and returns result`() {
        every { schedulerService.findById("job-1") } returns ScheduledJob(
            id = "job-1", name = "test", cronExpression = "0 0 * * *", jobType = ScheduledJobType.MCP_TOOL
        )
        every { schedulerService.trigger("job-1") } returns "triggered"

        val response = controller.triggerJob("job-1", exchange(userId = "admin-1", role = UserRole.ADMIN)).block()
        @Suppress("UNCHECKED_CAST")
        val result = response?.body as Map<String, Any>

        assertEquals(HttpStatus.OK, response.statusCode) { "Admin trigger should succeed" }
        assertEquals("triggered", result["result"]) { "Trigger response should include execution result" }
    }

    @Test
    fun `dryRunJob은(는) allows admin and returns dry run marker`() {
        every { schedulerService.findById("job-1") } returns ScheduledJob(
            id = "job-1", name = "test", cronExpression = "0 0 * * *", jobType = ScheduledJobType.MCP_TOOL
        )
        every { schedulerService.dryRun("job-1") } returns "preview"

        val response = controller.dryRunJob("job-1", exchange(userId = "admin-1", role = UserRole.ADMIN)).block()
        @Suppress("UNCHECKED_CAST")
        val result = response?.body as Map<String, Any>

        assertEquals(HttpStatus.OK, response.statusCode) { "Admin dry-run should succeed" }
        assertEquals("preview", result["result"]) { "Dry-run response should include execution result" }
        assertTrue(result["dryRun"] as Boolean) { "Dry-run response should expose dryRun=true" }
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
