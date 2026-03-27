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

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "비관리자 목록 조회는 403이어야 한다" }
        verify(exactly = 0) { schedulerService.list() }
    }

    @Test
    fun `getJob은(는) rejects non-admin`() {
        val response = controller.getJob("job-1", exchange(userId = "user-1", role = UserRole.USER))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "비관리자 단건 조회는 403이어야 한다" }
        verify(exactly = 0) { schedulerService.findById(any()) }
    }

    @Test
    fun `listJobs은(는) rejects ADMIN_MANAGER`() {
        val response = controller.listJobs(
            offset = 0, limit = 50,
            exchange = exchange(userId = "manager-1", role = UserRole.ADMIN_MANAGER)
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "ADMIN_MANAGER 역할은 개발자용 스케줄러 제어에 접근할 수 없어야 한다"
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

        assertEquals(HttpStatus.OK, response.statusCode) { "관리자 목록 조회는 200이어야 한다" }
        assertEquals(0, result.items.size) { "빈 스케줄러 목록은 빈 items로 반환되어야 한다" }
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

        assertEquals(HttpStatus.OK, response.statusCode) {
            "ADMIN_DEVELOPER 역할의 목록 조회는 200이어야 한다"
        }
        assertEquals(0, result.items.size) { "서비스가 빈 목록을 반환하면 items도 비어있어야 한다" }
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

        assertEquals(HttpStatus.FORBIDDEN, response?.statusCode) {
            "비관리자 트리거 요청은 403이어야 한다"
        }
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

        assertEquals(HttpStatus.OK, response.statusCode) { "관리자 트리거 요청은 200이어야 한다" }
        assertEquals("triggered", result["result"]) { "트리거 응답에 실행 결과가 포함되어야 한다" }
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

        assertEquals(HttpStatus.OK, response.statusCode) { "관리자 드라이런 요청은 200이어야 한다" }
        assertEquals("preview", result["result"]) { "드라이런 응답에 실행 결과가 포함되어야 한다" }
        assertTrue(result["dryRun"] as Boolean) { "드라이런 응답에 dryRun=true가 포함되어야 한다" }
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
