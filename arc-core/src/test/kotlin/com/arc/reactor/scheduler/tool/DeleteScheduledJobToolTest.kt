package com.arc.reactor.scheduler.tool

import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DeleteScheduledJobToolTest {

    private val objectMapper = jacksonObjectMapper()
    private val schedulerService = mockk<DynamicSchedulerService>()
    private val tool = DeleteScheduledJobTool(schedulerService)

    private val sampleJob = ScheduledJob(
        id = "job-1",
        name = "Daily Summary",
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.AGENT,
        agentPrompt = "summarize"
    )

    @Test
    fun `has null category so it is always loaded`() {
        assertNull(tool.category,
            "Scheduler tools must have null category to be always available")
    }

    @Nested
    inner class DeleteById {

        @Test
        fun `deletes existing job by ID`() {
            every { schedulerService.findById("job-1") } returns sampleJob
            every { schedulerService.delete("job-1") } just runs

            val result = tool.delete_scheduled_job(jobId = "job-1")
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertEquals("deleted", response["status"], "Status should be 'deleted'")
            assertEquals("job-1", response["id"], "ID should match")
            assertEquals("Daily Summary", response["name"], "Name should match")
            verify(exactly = 1) { schedulerService.delete("job-1") }
        }

        @Test
        fun `returns error when job ID not found`() {
            every { schedulerService.findById("nonexistent") } returns null

            val result = tool.delete_scheduled_job(jobId = "nonexistent")
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("not found"),
                "Error should mention not found: ${response["error"]}")
        }
    }

    @Nested
    inner class DeleteByName {

        @Test
        fun `deletes existing job by name`() {
            every { schedulerService.findByName("Daily Summary") } returns sampleJob
            every { schedulerService.delete("job-1") } just runs

            val result = tool.delete_scheduled_job(jobName = "Daily Summary")
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertEquals("deleted", response["status"], "Status should be 'deleted'")
            assertEquals("job-1", response["id"], "ID should match")
            assertEquals("Daily Summary", response["name"], "Name should match")
            verify(exactly = 1) { schedulerService.delete("job-1") }
        }

        @Test
        fun `returns error when job name not found`() {
            every { schedulerService.findByName("No Such Job") } returns null

            val result = tool.delete_scheduled_job(jobName = "No Such Job")
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("not found"),
                "Error should mention not found: ${response["error"]}")
        }
    }

    @Nested
    inner class IdTakesPrecedenceOverName {

        @Test
        fun `uses jobId when both jobId and jobName are provided`() {
            every { schedulerService.findById("job-1") } returns sampleJob
            every { schedulerService.delete("job-1") } just runs

            val result = tool.delete_scheduled_job(jobId = "job-1", jobName = "Ignored Name")
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertEquals("deleted", response["status"], "Should delete by ID")
            verify(exactly = 1) { schedulerService.findById("job-1") }
            verify(exactly = 0) { schedulerService.findByName(any()) }
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `returns error when both jobId and jobName are blank`() {
            val result = tool.delete_scheduled_job(jobId = "  ", jobName = "  ")
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("required"),
                "Error should mention required: ${response["error"]}")
        }

        @Test
        fun `returns error when both are null`() {
            val result = tool.delete_scheduled_job()
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertTrue(response.containsKey("error"), "Should return error")
        }

        @Test
        fun `returns error JSON on unexpected exception during delete`() {
            every { schedulerService.findById("job-err") } returns sampleJob.copy(id = "job-err")
            every { schedulerService.delete("job-err") } throws
                RuntimeException("Store unavailable")

            val result = tool.delete_scheduled_job(jobId = "job-err")
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertTrue(response.containsKey("error"), "Should return error, not throw")
            assertTrue(response["error"]!!.contains("Store unavailable"),
                "Error should contain exception message: ${response["error"]}")
        }
    }
}
