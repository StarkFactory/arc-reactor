package com.arc.reactor.scheduler.tool

import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UpdateScheduledJobToolTest {

    private val objectMapper = jacksonObjectMapper()
    private val schedulerService = mockk<DynamicSchedulerService>()
    private val tool = UpdateScheduledJobTool(schedulerService)

    private val existingJob = ScheduledJob(
        id = "job-1",
        name = "Daily Summary",
        cronExpression = "0 0 9 * * *",
        timezone = "Asia/Seoul",
        jobType = ScheduledJobType.AGENT,
        agentPrompt = "Summarize tasks",
        slackChannelId = "C123",
        enabled = true
    )

    @Test
    fun `has null category so it is always loaded`() {
        assertNull(tool.category,
            "Scheduler tools must have null category to be always available")
    }

    @Nested
    inner class PartialUpdate {

        @Test
        fun `updates only cron expression, keeps other fields`() {
            every { schedulerService.findById("job-1") } returns existingJob
            val jobSlot = slot<ScheduledJob>()
            every { schedulerService.update("job-1", capture(jobSlot)) } answers {
                jobSlot.captured
            }

            val result = tool.update_scheduled_job(
                jobId = "job-1",
                cronExpression = "0 0 10 * * *"
            )

            val response = objectMapper.readValue<Map<String, Any?>>(result)
            assertEquals("updated", response["status"], "Status should be 'updated'")
            assertEquals("0 0 10 * * *", response["cronExpression"], "Cron should be updated")

            val captured = jobSlot.captured
            assertEquals("Summarize tasks", captured.agentPrompt, "Prompt should be preserved")
            assertEquals("C123", captured.slackChannelId, "Slack channel should be preserved")
            assertEquals("Asia/Seoul", captured.timezone, "Timezone should be preserved")
            assertEquals(true, captured.enabled, "Enabled should be preserved")
        }

        @Test
        fun `updates only enabled flag to disable a job`() {
            every { schedulerService.findById("job-1") } returns existingJob
            val jobSlot = slot<ScheduledJob>()
            every { schedulerService.update("job-1", capture(jobSlot)) } answers {
                jobSlot.captured
            }

            val result = tool.update_scheduled_job(
                jobId = "job-1",
                enabled = false
            )

            val response = objectMapper.readValue<Map<String, Any?>>(result)
            assertEquals("updated", response["status"], "Status should be 'updated'")
            assertEquals(false, response["enabled"], "Should be disabled")

            assertEquals("0 0 9 * * *", jobSlot.captured.cronExpression,
                "Cron should be preserved")
        }

        @Test
        fun `clears slackChannelId when empty string is passed`() {
            every { schedulerService.findById("job-1") } returns existingJob
            val jobSlot = slot<ScheduledJob>()
            every { schedulerService.update("job-1", capture(jobSlot)) } answers {
                jobSlot.captured
            }

            val result = tool.update_scheduled_job(
                jobId = "job-1",
                slackChannelId = ""
            )

            val response = objectMapper.readValue<Map<String, Any?>>(result)
            assertEquals("updated", response["status"], "Status should be 'updated'")
            assertNull(jobSlot.captured.slackChannelId,
                "Slack channel should be cleared to null when empty string is passed")
            assertNull(response["slackChannelId"],
                "Response should show null slackChannelId")
        }

        @Test
        fun `updates prompt and slack channel together`() {
            every { schedulerService.findById("job-1") } returns existingJob
            val jobSlot = slot<ScheduledJob>()
            every { schedulerService.update("job-1", capture(jobSlot)) } answers {
                jobSlot.captured
            }

            tool.update_scheduled_job(
                jobId = "job-1",
                agentPrompt = "New prompt",
                slackChannelId = "C999"
            )

            val captured = jobSlot.captured
            assertEquals("New prompt", captured.agentPrompt, "Prompt should be updated")
            assertEquals("C999", captured.slackChannelId, "Channel should be updated")
            assertEquals("0 0 9 * * *", captured.cronExpression, "Cron should be preserved")
        }
    }

    @Nested
    inner class LookupByName {

        @Test
        fun `finds job by name and updates`() {
            every { schedulerService.findByName("Daily Summary") } returns existingJob
            val jobSlot = slot<ScheduledJob>()
            every { schedulerService.update("job-1", capture(jobSlot)) } answers {
                jobSlot.captured
            }

            val result = tool.update_scheduled_job(
                jobName = "Daily Summary",
                cronExpression = "0 0 10 * * *"
            )

            val response = objectMapper.readValue<Map<String, Any?>>(result)
            assertEquals("updated", response["status"], "Should update by name")
            assertEquals("job-1", response["id"], "Should use the resolved ID")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `returns error when neither jobId nor jobName provided`() {
            val result = tool.update_scheduled_job(cronExpression = "0 0 10 * * *")
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("required"),
                "Error should mention required: ${response["error"]}")
        }

        @Test
        fun `returns error when job not found by ID`() {
            every { schedulerService.findById("nonexistent") } returns null

            val result = tool.update_scheduled_job(
                jobId = "nonexistent",
                cronExpression = "0 0 10 * * *"
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("not found"),
                "Error should mention not found: ${response["error"]}")
        }

        @Test
        fun `returns error when job not found by name`() {
            every { schedulerService.findByName("No Such Job") } returns null

            val result = tool.update_scheduled_job(
                jobName = "No Such Job",
                enabled = false
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error")
        }

        @Test
        fun `returns error on invalid cron expression`() {
            every { schedulerService.findById("job-1") } returns existingJob
            every { schedulerService.update(any(), any()) } throws
                IllegalArgumentException("Invalid cron expression: bad")

            val result = tool.update_scheduled_job(
                jobId = "job-1",
                cronExpression = "bad"
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("cron"),
                "Error should mention cron: ${response["error"]}")
        }

        @Test
        fun `returns error JSON on unexpected exception`() {
            every { schedulerService.findById("job-1") } returns existingJob
            every { schedulerService.update(any(), any()) } throws
                RuntimeException("DB error")

            val result = tool.update_scheduled_job(
                jobId = "job-1",
                cronExpression = "0 0 10 * * *"
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error, not throw")
        }
    }
}
