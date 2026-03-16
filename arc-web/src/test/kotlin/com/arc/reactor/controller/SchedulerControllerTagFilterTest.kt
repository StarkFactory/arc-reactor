package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class SchedulerControllerTagFilterTest {

    private val schedulerService = mockk<DynamicSchedulerService>()
    private val controller = SchedulerController(schedulerService)

    @Nested
    inner class ListJobsTagFiltering {

        @Test
        fun `listJobs without tag returns all jobs`() {
            every { schedulerService.list() } returns listOf(
                job("job-1", tags = setOf("daily")),
                job("job-2", tags = setOf("weekly")),
                job("job-3", tags = emptySet())
            )

            val response = controller.listJobs(
                tag = null,
                offset = 0, limit = 50,
                exchange = adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode,
                "Unfiltered list should return 200 OK")
            @Suppress("UNCHECKED_CAST")
            val result = response.body as PaginatedResponse<ScheduledJobResponse>
            assertEquals(3, result.total,
                "All 3 jobs should be returned when no tag filter is applied")
        }

        @Test
        fun `listJobs with tag filters to matching jobs only`() {
            every { schedulerService.list() } returns listOf(
                job("job-1", tags = setOf("daily", "reporting")),
                job("job-2", tags = setOf("weekly")),
                job("job-3", tags = setOf("daily", "alerts"))
            )

            val response = controller.listJobs(
                tag = "daily",
                offset = 0, limit = 50,
                exchange = adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode,
                "Filtered list should return 200 OK")
            @Suppress("UNCHECKED_CAST")
            val result = response.body as PaginatedResponse<ScheduledJobResponse>
            assertEquals(2, result.total,
                "Only jobs with 'daily' tag should be returned")
            assertTrue(result.items.all { "daily" in it.tags },
                "All returned jobs should have the 'daily' tag")
        }

        @Test
        fun `listJobs with non-matching tag returns empty`() {
            every { schedulerService.list() } returns listOf(
                job("job-1", tags = setOf("daily"))
            )

            val response = controller.listJobs(
                tag = "nonexistent",
                offset = 0, limit = 50,
                exchange = adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode,
                "Non-matching tag filter should still return 200 OK")
            @Suppress("UNCHECKED_CAST")
            val result = response.body as PaginatedResponse<ScheduledJobResponse>
            assertEquals(0, result.total,
                "No jobs should match a nonexistent tag")
        }

        @Test
        fun `listJobs with blank tag returns all jobs`() {
            every { schedulerService.list() } returns listOf(
                job("job-1", tags = setOf("daily"))
            )

            val response = controller.listJobs(
                tag = "  ",
                offset = 0, limit = 50,
                exchange = adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode,
                "Blank tag filter should return 200 OK")
            @Suppress("UNCHECKED_CAST")
            val result = response.body as PaginatedResponse<ScheduledJobResponse>
            assertEquals(1, result.total,
                "Blank tag should be treated as no filter, returning all jobs")
        }

        @Test
        fun `listJobs tag filter combined with pagination`() {
            every { schedulerService.list() } returns listOf(
                job("job-1", tags = setOf("daily")),
                job("job-2", tags = setOf("daily")),
                job("job-3", tags = setOf("daily")),
                job("job-4", tags = setOf("weekly"))
            )

            val response = controller.listJobs(
                tag = "daily",
                offset = 1, limit = 1,
                exchange = adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode,
                "Paginated tag filter should return 200 OK")
            @Suppress("UNCHECKED_CAST")
            val result = response.body as PaginatedResponse<ScheduledJobResponse>
            assertEquals(3, result.total,
                "Total should reflect all daily-tagged jobs before pagination")
            assertEquals(1, result.items.size,
                "Only 1 item should be returned with limit=1")
            assertEquals(1, result.offset,
                "Offset should be preserved in the response")
        }
    }

    // -- Helpers ---------------------------------------------------------------

    private fun job(
        name: String,
        tags: Set<String> = emptySet()
    ) = ScheduledJob(
        id = name,
        name = name,
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.MCP_TOOL,
        mcpServerName = "test-server",
        toolName = "test_tool",
        tags = tags,
        enabled = true
    )

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attrs = mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to "admin-1",
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN
        )
        every { exchange.attributes } returns attrs
        return exchange
    }
}
