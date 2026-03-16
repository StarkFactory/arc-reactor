package com.arc.reactor.scheduler

import com.arc.reactor.mcp.McpManager
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.scheduling.TaskScheduler

@Tag("safety")
class DynamicSchedulerServiceValidationTest {

    private val store = mockk<ScheduledJobStore>()
    private val taskScheduler = mockk<TaskScheduler>(relaxed = true)
    private val mcpManager = mockk<McpManager>()
    private val service = DynamicSchedulerService(
        store = store,
        taskScheduler = taskScheduler,
        mcpManager = mcpManager
    )

    // -- job name validation --

    @Test
    fun `create rejects blank job name`() {
        val job = validMcpJob().copy(name = "")

        val ex = assertThrows<IllegalArgumentException>("blank name should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "Job name must not be blank"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `create rejects whitespace-only job name`() {
        val job = validMcpJob().copy(name = "   ")

        val ex = assertThrows<IllegalArgumentException>("whitespace-only name should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "Job name must not be blank"
        verify(exactly = 0) { store.save(any()) }
    }

    // -- executionTimeoutMs validation --

    @Test
    fun `create allows null executionTimeoutMs`() {
        val job = validMcpJob().copy(executionTimeoutMs = null)
        every { store.save(job) } returns job.copy(id = "job-1")

        service.create(job)

        verify(exactly = 1) { store.save(job) }
    }

    @Test
    fun `create allows zero executionTimeoutMs as unlimited`() {
        val job = validMcpJob().copy(executionTimeoutMs = 0L)
        every { store.save(job) } returns job.copy(id = "job-1")

        service.create(job)

        verify(exactly = 1) { store.save(job) }
    }

    @Test
    fun `create rejects executionTimeoutMs below 1000ms`() {
        val job = validMcpJob().copy(executionTimeoutMs = 500L)

        val ex = assertThrows<IllegalArgumentException>("timeout below 1000 should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "executionTimeoutMs"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `create rejects executionTimeoutMs above 3600000ms`() {
        val job = validMcpJob().copy(executionTimeoutMs = 3600001L)

        val ex = assertThrows<IllegalArgumentException>("timeout above 3600000 should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "executionTimeoutMs"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `create accepts executionTimeoutMs at boundary values`() {
        val jobMin = validMcpJob().copy(executionTimeoutMs = 1000L)
        every { store.save(jobMin) } returns jobMin.copy(id = "job-1")
        service.create(jobMin)
        verify(exactly = 1) { store.save(jobMin) }

        val jobMax = validMcpJob().copy(executionTimeoutMs = 3600000L)
        every { store.save(jobMax) } returns jobMax.copy(id = "job-2")
        service.create(jobMax)
        verify(exactly = 1) { store.save(jobMax) }
    }

    // -- retry config validation --

    @Test
    fun `create rejects retryOnFailure with maxRetryCount 0`() {
        val job = validMcpJob().copy(retryOnFailure = true, maxRetryCount = 0)

        val ex = assertThrows<IllegalArgumentException>("retry with 0 max count should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "maxRetryCount"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `create rejects retryOnFailure with negative maxRetryCount`() {
        val job = validMcpJob().copy(retryOnFailure = true, maxRetryCount = -1)

        val ex = assertThrows<IllegalArgumentException>("retry with negative max count should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "maxRetryCount"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `create allows retryOnFailure false with any maxRetryCount`() {
        val job = validMcpJob().copy(retryOnFailure = false, maxRetryCount = 0)
        every { store.save(job) } returns job.copy(id = "job-1")

        service.create(job)

        verify(exactly = 1) { store.save(job) }
    }

    // -- MCP_TOOL required fields --

    @Test
    fun `create rejects MCP_TOOL job with blank mcpServerName`() {
        val job = validMcpJob().copy(mcpServerName = "")

        val ex = assertThrows<IllegalArgumentException>("blank mcpServerName should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "mcpServerName"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `create rejects MCP_TOOL job with null mcpServerName`() {
        val job = validMcpJob().copy(mcpServerName = null)

        val ex = assertThrows<IllegalArgumentException>("null mcpServerName should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "mcpServerName"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `create rejects MCP_TOOL job with blank toolName`() {
        val job = validMcpJob().copy(toolName = "")

        val ex = assertThrows<IllegalArgumentException>("blank toolName should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "toolName"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `create rejects MCP_TOOL job with null toolName`() {
        val job = validMcpJob().copy(toolName = null)

        val ex = assertThrows<IllegalArgumentException>("null toolName should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "toolName"
        verify(exactly = 0) { store.save(any()) }
    }

    // -- AGENT required fields --

    @Test
    fun `create rejects AGENT job with blank agentPrompt`() {
        val job = validAgentJob().copy(agentPrompt = "")

        val ex = assertThrows<IllegalArgumentException>("blank agentPrompt should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "agentPrompt"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `create rejects AGENT job with null agentPrompt`() {
        val job = validAgentJob().copy(agentPrompt = null)

        val ex = assertThrows<IllegalArgumentException>("null agentPrompt should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "agentPrompt"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `create accepts valid AGENT job`() {
        val job = validAgentJob()
        every { store.save(job) } returns job.copy(id = "job-1")

        service.create(job)

        verify(exactly = 1) { store.save(job) }
    }

    // -- update also validates --

    @Test
    fun `update rejects blank job name`() {
        val job = validMcpJob().copy(name = "")

        assertThrows<IllegalArgumentException>("update should also validate") {
            service.update("job-1", job)
        }
        verify(exactly = 0) { store.update(any(), any()) }
    }

    // -- helpers --

    private fun validMcpJob(): ScheduledJob = ScheduledJob(
        id = "",
        name = "daily-digest",
        cronExpression = "0 0 9 * * *",
        timezone = "Asia/Seoul",
        jobType = ScheduledJobType.MCP_TOOL,
        mcpServerName = "server-a",
        toolName = "tool-a",
        toolArguments = mapOf("k" to "v"),
        enabled = true
    )

    private fun validAgentJob(): ScheduledJob = ScheduledJob(
        id = "",
        name = "agent-summary",
        cronExpression = "0 0 9 * * *",
        timezone = "Asia/Seoul",
        jobType = ScheduledJobType.AGENT,
        agentPrompt = "Summarize today's events",
        enabled = true
    )
}
