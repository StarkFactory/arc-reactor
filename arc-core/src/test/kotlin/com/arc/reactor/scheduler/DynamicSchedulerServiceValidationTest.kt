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
/**
 * DynamicSchedulerService의 유효성 검사에 대한 테스트.
 *
 * 스케줄된 작업 등록/수정 시 유효성 검사를 검증합니다.
 */
class DynamicSchedulerServiceValidationTest {

    private val store = mockk<ScheduledJobStore>()
    private val taskScheduler = mockk<TaskScheduler>(relaxed = true)
    private val mcpManager = mockk<McpManager>()
    private val service = DynamicSchedulerService(
        store = store,
        taskScheduler = taskScheduler,
        mcpManager = mcpManager
    )

    // -- 작업 이름 유효성 검사 --

    @Test
    fun `rejects blank job name를 생성한다`() {
        val job = validMcpJob().copy(name = "")

        val ex = assertThrows<IllegalArgumentException>("blank name should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "Job name must not be blank"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `rejects whitespace-only job name를 생성한다`() {
        val job = validMcpJob().copy(name = "   ")

        val ex = assertThrows<IllegalArgumentException>("whitespace-only name should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "Job name must not be blank"
        verify(exactly = 0) { store.save(any()) }
    }

    // -- executionTimeoutMs 유효성 검사 --

    @Test
    fun `allows null executionTimeoutMs를 생성한다`() {
        val job = validMcpJob().copy(executionTimeoutMs = null)
        every { store.save(job) } returns job.copy(id = "job-1")

        service.create(job)

        verify(exactly = 1) { store.save(job) }
    }

    @Test
    fun `allows zero executionTimeoutMs as unlimited를 생성한다`() {
        val job = validMcpJob().copy(executionTimeoutMs = 0L)
        every { store.save(job) } returns job.copy(id = "job-1")

        service.create(job)

        verify(exactly = 1) { store.save(job) }
    }

    @Test
    fun `rejects executionTimeoutMs below 1000ms를 생성한다`() {
        val job = validMcpJob().copy(executionTimeoutMs = 500L)

        val ex = assertThrows<IllegalArgumentException>("timeout below 1000 should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "executionTimeoutMs"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `rejects executionTimeoutMs above 3600000ms를 생성한다`() {
        val job = validMcpJob().copy(executionTimeoutMs = 3600001L)

        val ex = assertThrows<IllegalArgumentException>("timeout above 3600000 should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "executionTimeoutMs"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `accepts executionTimeoutMs at boundary values를 생성한다`() {
        val jobMin = validMcpJob().copy(executionTimeoutMs = 1000L)
        every { store.save(jobMin) } returns jobMin.copy(id = "job-1")
        service.create(jobMin)
        verify(exactly = 1) { store.save(jobMin) }

        val jobMax = validMcpJob().copy(executionTimeoutMs = 3600000L)
        every { store.save(jobMax) } returns jobMax.copy(id = "job-2")
        service.create(jobMax)
        verify(exactly = 1) { store.save(jobMax) }
    }

    // -- 재시도 설정 유효성 검사 --

    @Test
    fun `rejects retryOnFailure with maxRetryCount 0를 생성한다`() {
        val job = validMcpJob().copy(retryOnFailure = true, maxRetryCount = 0)

        val ex = assertThrows<IllegalArgumentException>("retry with 0 max count should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "maxRetryCount"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `rejects retryOnFailure with negative maxRetryCount를 생성한다`() {
        val job = validMcpJob().copy(retryOnFailure = true, maxRetryCount = -1)

        val ex = assertThrows<IllegalArgumentException>("retry with negative max count should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "maxRetryCount"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `allows retryOnFailure false with any maxRetryCount를 생성한다`() {
        val job = validMcpJob().copy(retryOnFailure = false, maxRetryCount = 0)
        every { store.save(job) } returns job.copy(id = "job-1")

        service.create(job)

        verify(exactly = 1) { store.save(job) }
    }

    // -- MCP_TOOL 필수 필드 --

    @Test
    fun `rejects MCP_TOOL job with blank mcpServerName를 생성한다`() {
        val job = validMcpJob().copy(mcpServerName = "")

        val ex = assertThrows<IllegalArgumentException>("blank mcpServerName should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "mcpServerName"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `rejects MCP_TOOL job with null mcpServerName를 생성한다`() {
        val job = validMcpJob().copy(mcpServerName = null)

        val ex = assertThrows<IllegalArgumentException>("null mcpServerName should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "mcpServerName"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `rejects MCP_TOOL job with blank toolName를 생성한다`() {
        val job = validMcpJob().copy(toolName = "")

        val ex = assertThrows<IllegalArgumentException>("blank toolName should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "toolName"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `rejects MCP_TOOL job with null toolName를 생성한다`() {
        val job = validMcpJob().copy(toolName = null)

        val ex = assertThrows<IllegalArgumentException>("null toolName should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "toolName"
        verify(exactly = 0) { store.save(any()) }
    }

    // -- AGENT 필수 필드 --

    @Test
    fun `rejects AGENT job with blank agentPrompt를 생성한다`() {
        val job = validAgentJob().copy(agentPrompt = "")

        val ex = assertThrows<IllegalArgumentException>("blank agentPrompt should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "agentPrompt"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `rejects AGENT job with null agentPrompt를 생성한다`() {
        val job = validAgentJob().copy(agentPrompt = null)

        val ex = assertThrows<IllegalArgumentException>("null agentPrompt should be rejected") {
            service.create(job)
        }
        ex.message shouldContain "agentPrompt"
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `accepts valid AGENT job를 생성한다`() {
        val job = validAgentJob()
        every { store.save(job) } returns job.copy(id = "job-1")

        service.create(job)

        verify(exactly = 1) { store.save(job) }
    }

    // -- 업데이트도 유효성 검사를 수행 --

    @Test
    fun `rejects blank job name를 업데이트한다`() {
        val job = validMcpJob().copy(name = "")

        assertThrows<IllegalArgumentException>("update should also validate") {
            service.update("job-1", job)
        }
        verify(exactly = 0) { store.update(any(), any()) }
    }

    // -- 헬퍼 --

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
