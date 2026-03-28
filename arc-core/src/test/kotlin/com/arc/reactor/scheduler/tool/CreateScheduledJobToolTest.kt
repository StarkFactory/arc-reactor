package com.arc.reactor.scheduler.tool

import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * CreateScheduledJobTool에 대한 테스트.
 *
 * 스케줄된 작업 생성 도구의 동작을 검증합니다.
 */
class CreateScheduledJobToolTest {

    private val objectMapper = jacksonObjectMapper()
    private val schedulerService = mockk<DynamicSchedulerService>()
    private val tool = CreateScheduledJobTool(schedulerService, "Asia/Seoul")

    @Test
    fun `has null category so it은(는) always loaded이다`() {
        assertNull(tool.category,
            "Scheduler tools must have null category to be always available")
    }

    @Nested
    inner class SuccessfulCreation {

        @Test
        fun `AGENT job with valid 6-field cron를 생성한다`() {
            every { schedulerService.findByName(any()) } returns null
            val jobSlot = slot<ScheduledJob>()
            every { schedulerService.create(capture(jobSlot)) } answers {
                jobSlot.captured.copy(id = "job-123")
            }

            val result = tool.create_scheduled_job(
                name = "Daily Summary",
                cronExpression = "0 0 9 * * *",
                agentPrompt = "Summarize today's tasks",
                slackChannelId = "C1234567",
                timezone = "Asia/Seoul"
            )

            val response = objectMapper.readValue<Map<String, Any?>>(result)
            assertEquals("created", response["status"], "Status should be 'created'")
            assertEquals("job-123", response["id"], "ID should match")
            assertEquals("Daily Summary", response["name"], "Name should match")
            assertEquals("0 0 9 * * *", response["cronExpression"], "Cron should match")
            assertEquals("Asia/Seoul", response["timezone"], "Timezone should match")
            assertEquals("C1234567", response["slackChannelId"], "Slack channel should match")

            val captured = jobSlot.captured
            assertEquals(ScheduledJobType.AGENT, captured.jobType, "Job type should be AGENT")
            assertEquals("Summarize today's tasks", captured.agentPrompt, "Prompt should match")
        }

        @Test
        fun `not specified일 때 default timezone를 사용한다`() {
            every { schedulerService.findByName(any()) } returns null
            val jobSlot = slot<ScheduledJob>()
            every { schedulerService.create(capture(jobSlot)) } answers {
                jobSlot.captured.copy(id = "job-456")
            }

            tool.create_scheduled_job(
                name = "Test Job",
                cronExpression = "0 0 9 * * *",
                agentPrompt = "test"
            )

            assertEquals("Asia/Seoul", jobSlot.captured.timezone,
                "Default timezone should be Asia/Seoul")
        }

        @Test
        fun `not provided일 때 slackChannelId은(는) null이다`() {
            every { schedulerService.findByName(any()) } returns null
            val jobSlot = slot<ScheduledJob>()
            every { schedulerService.create(capture(jobSlot)) } answers {
                jobSlot.captured.copy(id = "job-789")
            }

            tool.create_scheduled_job(
                name = "No Slack",
                cronExpression = "0 0 9 * * *",
                agentPrompt = "test"
            )

            assertNull(jobSlot.captured.slackChannelId,
                "Slack channel should be null when not provided")
        }

        @Test
        fun `blank slackChannelId은(는) normalized to null이다`() {
            every { schedulerService.findByName(any()) } returns null
            val jobSlot = slot<ScheduledJob>()
            every { schedulerService.create(capture(jobSlot)) } answers {
                jobSlot.captured.copy(id = "job-norm")
            }

            tool.create_scheduled_job(
                name = "Blank Channel",
                cronExpression = "0 0 9 * * *",
                agentPrompt = "test",
                slackChannelId = "   "
            )

            assertNull(jobSlot.captured.slackChannelId,
                "Blank slackChannelId should be normalized to null")
        }
    }

    @Nested
    inner class ValidationErrors {

        @Test
        fun `name is blank일 때 error를 반환한다`() {
            val result = tool.create_scheduled_job(
                name = "  ",
                cronExpression = "0 0 9 * * *",
                agentPrompt = "test"
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("name"), "Error should mention name")
        }

        @Test
        fun `cronExpression is blank일 때 error를 반환한다`() {
            val result = tool.create_scheduled_job(
                name = "Test",
                cronExpression = "",
                agentPrompt = "test"
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("cronExpression"),
                "Error should mention cronExpression")
        }

        @Test
        fun `agentPrompt is blank일 때 error를 반환한다`() {
            val result = tool.create_scheduled_job(
                name = "Test",
                cronExpression = "0 0 9 * * *",
                agentPrompt = "   "
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("agentPrompt"),
                "Error should mention agentPrompt")
        }

        @Test
        fun `error on invalid cron expression를 반환한다`() {
            every { schedulerService.findByName(any()) } returns null
            every { schedulerService.create(any()) } throws
                IllegalArgumentException("Invalid cron expression: bad-cron")

            val result = tool.create_scheduled_job(
                name = "Bad Cron",
                cronExpression = "bad-cron",
                agentPrompt = "test"
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error")
            assertTrue(response["error"]!!.contains("스케줄 작업 생성 중 오류가 발생했습니다"),
                "Error should mention cron: ${response["error"]}")
        }
    }

    @Nested
    inner class DuplicatePrevention {

        @Test
        fun `job with same name already exists일 때 error를 반환한다`() {
            every { schedulerService.findByName("Daily Summary") } returns
                ScheduledJob(
                    id = "existing-1",
                    name = "Daily Summary",
                    cronExpression = "0 0 9 * * *",
                    jobType = ScheduledJobType.AGENT,
                    agentPrompt = "test"
                )

            val result = tool.create_scheduled_job(
                name = "Daily Summary",
                cronExpression = "0 0 10 * * *",
                agentPrompt = "different prompt"
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error for duplicate name")
            assertTrue(response["error"]!!.contains("already exists"),
                "Error should mention already exists: ${response["error"]}")
            verify(exactly = 0) { schedulerService.create(any()) }
        }
    }

    @Nested
    inner class UnexpectedExceptions {

        @Test
        fun `error JSON on unexpected RuntimeException를 반환한다`() {
            every { schedulerService.findByName(any()) } returns null
            every { schedulerService.create(any()) } throws
                RuntimeException("Database connection failed")

            val result = tool.create_scheduled_job(
                name = "Failing Job",
                cronExpression = "0 0 9 * * *",
                agentPrompt = "test"
            )

            val response = objectMapper.readValue<Map<String, String>>(result)
            assertTrue(response.containsKey("error"), "Should return error, not throw")
            assertTrue(response["error"]!!.contains("스케줄 작업 생성 중 오류가 발생했습니다"),
                "Error should contain Korean error message: ${response["error"]}")
        }
    }
}
