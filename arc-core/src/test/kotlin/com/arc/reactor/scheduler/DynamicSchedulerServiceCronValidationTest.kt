package com.arc.reactor.scheduler

import com.arc.reactor.mcp.McpManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.scheduling.TaskScheduler

@Tag("safety")
class DynamicSchedulerServiceCronValidationTest {

    private val store = mockk<ScheduledJobStore>()
    private val taskScheduler = mockk<TaskScheduler>(relaxed = true)
    private val mcpManager = mockk<McpManager>()
    private val service = DynamicSchedulerService(
        store = store,
        taskScheduler = taskScheduler,
        mcpManager = mcpManager
    )

    @Test
    fun `fails fast on invalid cron and does not persist를 생성한다`() {
        val job = validJob().copy(cronExpression = "invalid-cron")
        every { store.save(any()) } answers { firstArg() }

        assertThrows<IllegalArgumentException> {
            service.create(job)
        }

        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `fails fast on invalid timezone and does not persist를 생성한다`() {
        val job = validJob().copy(timezone = "Invalid/Timezone")
        every { store.save(any()) } answers { firstArg() }

        assertThrows<IllegalArgumentException> {
            service.create(job)
        }

        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `fails fast on invalid cron and does not persist를 업데이트한다`() {
        val job = validJob().copy(cronExpression = "nope")
        every { store.update(any(), any()) } answers { secondArg() }

        assertThrows<IllegalArgumentException> {
            service.update("job-1", job)
        }

        verify(exactly = 0) { store.update(any(), any()) }
    }

    @Test
    fun `persists when cron and timezone are valid를 생성한다`() {
        val job = validJob()
        every { store.save(job) } returns job.copy(id = "job-1")

        val saved = service.create(job)

        assertEquals("job-1", saved.id)
        verify(exactly = 1) { store.save(job) }
    }

    private fun validJob(): ScheduledJob = ScheduledJob(
        id = "",
        name = "daily-digest",
        cronExpression = "0 0 9 * * *",
        timezone = "Asia/Seoul",
        mcpServerName = "server-a",
        toolName = "tool-a",
        toolArguments = mapOf("k" to "v"),
        enabled = true
    )
}
