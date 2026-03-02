package com.arc.reactor.scheduler.tool

import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.JobExecutionStatus
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ListScheduledJobsToolTest {

    private val objectMapper = jacksonObjectMapper()
    private val schedulerService = mockk<DynamicSchedulerService>()
    private val tool = ListScheduledJobsTool(schedulerService)

    @Test
    fun `has null category so it is always loaded`() {
        assertNull(tool.category,
            "Scheduler tools must have null category to be always available")
    }

    @Nested
    inner class ListJobs {

        @Test
        fun `returns empty list when no jobs exist`() {
            every { schedulerService.list() } returns emptyList()

            val result = tool.list_scheduled_jobs()
            val response = objectMapper.readValue<Map<String, Any>>(result)

            assertEquals(0, response["total"], "Total should be 0")
            @Suppress("UNCHECKED_CAST")
            val jobs = response["jobs"] as List<*>
            assertEquals(0, jobs.size, "Jobs list should be empty")
        }

        @Test
        fun `returns all jobs with summary fields`() {
            every { schedulerService.list() } returns listOf(
                ScheduledJob(
                    id = "job-1",
                    name = "Daily Summary",
                    cronExpression = "0 0 9 * * *",
                    timezone = "Asia/Seoul",
                    jobType = ScheduledJobType.AGENT,
                    agentPrompt = "summarize",
                    slackChannelId = "C123",
                    enabled = true,
                    lastStatus = JobExecutionStatus.SUCCESS
                ),
                ScheduledJob(
                    id = "job-2",
                    name = "Weather Check",
                    cronExpression = "0 0 */2 * * *",
                    jobType = ScheduledJobType.MCP_TOOL,
                    mcpServerName = "weather",
                    toolName = "get_weather",
                    enabled = false
                )
            )

            val result = tool.list_scheduled_jobs()
            val response = objectMapper.readValue<Map<String, Any>>(result)

            assertEquals(2, response["total"], "Total should be 2")

            @Suppress("UNCHECKED_CAST")
            val jobs = response["jobs"] as List<Map<String, Any?>>
            assertEquals("job-1", jobs[0]["id"], "First job ID should match")
            assertEquals("Daily Summary", jobs[0]["name"], "First job name should match")
            assertEquals("AGENT", jobs[0]["jobType"], "First job type should be AGENT")
            assertEquals("C123", jobs[0]["slackChannelId"], "Slack channel should match")
            assertEquals(true, jobs[0]["enabled"], "First job should be enabled")
            assertEquals("SUCCESS", jobs[0]["lastStatus"], "Last status should be SUCCESS")

            assertEquals("job-2", jobs[1]["id"], "Second job ID should match")
            assertEquals(false, jobs[1]["enabled"], "Second job should be disabled")
        }

        @Test
        fun `does not expose agentPrompt in summary`() {
            every { schedulerService.list() } returns listOf(
                ScheduledJob(
                    id = "job-1",
                    name = "Test",
                    cronExpression = "0 0 9 * * *",
                    jobType = ScheduledJobType.AGENT,
                    agentPrompt = "sensitive prompt"
                )
            )

            val result = tool.list_scheduled_jobs()
            val response = objectMapper.readValue<Map<String, Any>>(result)
            @Suppress("UNCHECKED_CAST")
            val jobs = response["jobs"] as List<Map<String, Any?>>
            assertTrue(!jobs[0].containsKey("agentPrompt"),
                "Summary should not expose agentPrompt")
        }
    }

    @Nested
    inner class UnexpectedExceptions {

        @Test
        fun `returns error JSON on unexpected exception`() {
            every { schedulerService.list() } throws
                RuntimeException("Store unavailable")

            val result = tool.list_scheduled_jobs()
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertTrue(response.containsKey("error"), "Should return error, not throw")
            assertTrue(response["error"]!!.contains("Store unavailable"),
                "Error should contain exception message: ${response["error"]}")
        }
    }
}
