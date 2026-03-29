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

/**
 * UpdateScheduledJobTool에 대한 테스트.
 *
 * 스케줄된 작업 수정 도구의 동작을 검증합니다.
 */
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
    fun `has null category so it은(는) always loaded이다`() {
        assertNull(tool.category,
            "Scheduler tools must have null category to be always available")
    }

    @Nested
    inner class PartialUpdate {

        @Test
        fun `only cron expression, keeps other fields를 업데이트한다`() {
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
        fun `only enabled flag to disable a job를 업데이트한다`() {
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
        fun `clears slackChannelId when empty string은(는) passed이다`() {
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
        fun `prompt and slack channel together를 업데이트한다`() {
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
        fun `job by name and updates를 찾는다`() {
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
        fun `neither jobId nor jobName provided일 때 error를 반환한다`() {
            val result = tool.update_scheduled_job(cronExpression = "0 0 10 * * *")
            val response = objectMapper.readValue<Map<String, String>>(result)

            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("required"),
                "Error should mention required: ${response["error"]}")
        }

        @Test
        fun `job not found by ID일 때 error를 반환한다`() {
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
        fun `job not found by name일 때 error를 반환한다`() {
            every { schedulerService.findByName("No Such Job") } returns null

            val result = tool.update_scheduled_job(
                jobName = "No Such Job",
                enabled = false
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error")
        }

        @Test
        fun `error on invalid cron expression를 반환한다`() {
            every { schedulerService.findById("job-1") } returns existingJob
            every { schedulerService.update(any(), any()) } throws
                IllegalArgumentException("유효하지 않은 cron 표현식: bad")

            val result = tool.update_scheduled_job(
                jobId = "job-1",
                cronExpression = "bad"
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("스케줄 작업 수정 중 오류가 발생했습니다"),
                "Error should mention cron: ${response["error"]}")
        }

        @Test
        fun `error JSON on unexpected exception를 반환한다`() {
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
