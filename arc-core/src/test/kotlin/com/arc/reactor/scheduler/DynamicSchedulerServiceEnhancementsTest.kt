package com.arc.reactor.scheduler

import com.arc.reactor.mcp.McpManager
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler
import java.util.concurrent.CopyOnWriteArrayList

class DynamicSchedulerServiceEnhancementsTest {

    // ── Execution history ─────────────────────────────────────────────────────

    @Nested
    inner class ExecutionHistory {

        @Test
        fun `execution history is recorded on SUCCESS`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockMcpManager(job, "tool-result")

            buildService(store, mcpManager = mcpManager, executionStore = execStore).trigger(job.id)

            assertEquals(1, execStore.saved.size,
                "Exactly one execution record should be saved on success")
            val exec = execStore.saved[0]
            assertEquals(job.id, exec.jobId, "Execution record should reference job id")
            assertEquals(JobExecutionStatus.SUCCESS, exec.status, "Status should be SUCCESS")
            assertTrue(exec.durationMs >= 0, "Duration should be non-negative")
            assertEquals(false, exec.dryRun, "dryRun should be false for normal execution")
        }

        @Test
        fun `execution history is recorded on FAILURE`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockk<McpManager>()
            val tool = mockk<ToolCallback>()

            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } throws RuntimeException("connection refused")
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)

            buildService(store, mcpManager = mcpManager, executionStore = execStore).trigger(job.id)

            assertEquals(1, execStore.saved.size,
                "Exactly one execution record should be saved on failure")
            assertEquals(JobExecutionStatus.FAILED, execStore.saved[0].status,
                "Status should be FAILED when tool throws")
        }

        @Test
        fun `getExecutions delegates to executionStore`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val execution = ScheduledJobExecution(
                id = "exec-1",
                jobId = job.id,
                jobName = job.name,
                status = JobExecutionStatus.SUCCESS
            )
            execStore.saved.add(execution)

            val service = buildService(store, executionStore = execStore)
            val result = service.getExecutions(job.id, 10)

            assertEquals(1, result.size, "getExecutions should return records from executionStore")
            assertEquals("exec-1", result[0].id, "Returned execution should match stored record")
        }
    }

    // ── Dry-run ───────────────────────────────────────────────────────────────

    @Nested
    inner class DryRun {

        @Test
        fun `dry-run does not update job status`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockMcpManager(job, "dry-result")

            buildService(store, mcpManager = mcpManager, executionStore = execStore).dryRun(job.id)

            assertEquals(null, store.lastStatus,
                "dryRun should NOT call store.updateExecutionResult()")
        }

        @Test
        fun `dry-run does not send Slack notifications`() {
            val job = mcpJob(slackChannelId = "C-TEST")
            val store = RecordingStore(job)
            val slackMessages = CopyOnWriteArrayList<Pair<String, String>>()
            val slackSender = SlackMessageSender { channel, text -> slackMessages.add(channel to text) }
            val mcpManager = mockMcpManager(job, "dry-result")

            buildService(
                store,
                mcpManager = mcpManager,
                slackSender = slackSender
            ).dryRun(job.id)

            assertEquals(0, slackMessages.size,
                "dryRun should NOT send Slack messages")
        }

        @Test
        fun `dry-run records to executionStore with dryRun=true`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockMcpManager(job, "dry-result")

            buildService(store, mcpManager = mcpManager, executionStore = execStore).dryRun(job.id)

            assertEquals(1, execStore.saved.size,
                "One execution record should be saved even for dry-run")
            assertTrue(execStore.saved[0].dryRun,
                "Execution record dryRun flag should be true")
        }
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    @Nested
    inner class Retry {

        @Test
        fun `retry succeeds on second attempt`() {
            val job = mcpJob(retryOnFailure = true, maxRetryCount = 3)
            val store = RecordingStore(job)
            val tool = mockk<ToolCallback>()
            val mcpManager = mockk<McpManager>()
            var callCount = 0

            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } answers {
                callCount++
                if (callCount == 1) throw RuntimeException("first attempt fails")
                "success on attempt $callCount"
            }
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)

            val result = buildService(store, mcpManager = mcpManager).trigger(job.id)

            assertEquals(JobExecutionStatus.SUCCESS, store.lastStatus,
                "Job should succeed after retry")
            assertTrue(result.contains("success"), "Result should reflect successful attempt")
            assertEquals(2, callCount, "Tool should have been called exactly twice")
        }

        @Test
        fun `all retries fail, job status is FAILED`() {
            val job = mcpJob(retryOnFailure = true, maxRetryCount = 2)
            val store = RecordingStore(job)
            val tool = mockk<ToolCallback>()
            val mcpManager = mockk<McpManager>()
            var callCount = 0

            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } answers {
                callCount++
                throw RuntimeException("always fails (attempt $callCount)")
            }
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)

            buildService(store, mcpManager = mcpManager).trigger(job.id)

            assertEquals(JobExecutionStatus.FAILED, store.lastStatus,
                "Job status should be FAILED when all retries are exhausted")
            assertEquals(2, callCount,
                "Tool should have been called exactly maxRetryCount times")
        }

        @Test
        fun `no retry when retryOnFailure=false`() {
            val job = mcpJob(retryOnFailure = false)
            val store = RecordingStore(job)
            val tool = mockk<ToolCallback>()
            val mcpManager = mockk<McpManager>()
            var callCount = 0

            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } answers {
                callCount++
                throw RuntimeException("fails once")
            }
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)

            buildService(store, mcpManager = mcpManager).trigger(job.id)

            assertEquals(1, callCount,
                "Tool should be called exactly once when retryOnFailure=false")
            assertEquals(JobExecutionStatus.FAILED, store.lastStatus,
                "Job status should be FAILED")
        }
    }

    // ── Timeout ───────────────────────────────────────────────────────────────

    @Nested
    inner class Timeout {

        @Test
        fun `timeout causes job to fail with timeout message`() {
            val job = mcpJob(executionTimeoutMs = 100)
            val store = RecordingStore(job)
            val tool = mockk<ToolCallback>()
            val mcpManager = mockk<McpManager>()

            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } coAnswers {
                delay(500)
                "should not reach here"
            }
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)

            val result = buildService(store, mcpManager = mcpManager).trigger(job.id)

            assertEquals(JobExecutionStatus.FAILED, store.lastStatus,
                "Job status should be FAILED on timeout")
            assertTrue(result.contains("timed out", ignoreCase = true),
                "Failure message should mention timeout")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mcpJob(
        retryOnFailure: Boolean = false,
        maxRetryCount: Int = 3,
        executionTimeoutMs: Long? = null,
        slackChannelId: String? = null
    ) = ScheduledJob(
        id = "job-1",
        name = "test-job",
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.MCP_TOOL,
        mcpServerName = "test-server",
        toolName = "test_tool",
        toolArguments = emptyMap(),
        retryOnFailure = retryOnFailure,
        maxRetryCount = maxRetryCount,
        executionTimeoutMs = executionTimeoutMs,
        slackChannelId = slackChannelId,
        enabled = true
    )

    private fun mockMcpManager(job: ScheduledJob, returnValue: String): McpManager {
        val mcpManager = mockk<McpManager>()
        val tool = mockk<ToolCallback>()
        every { tool.name } returns job.toolName!!
        coEvery { tool.call(any()) } returns returnValue
        coEvery { mcpManager.ensureConnected(any()) } returns true
        every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)
        return mcpManager
    }

    private fun buildService(
        store: ScheduledJobStore,
        mcpManager: McpManager = mockk(relaxed = true),
        slackSender: SlackMessageSender? = null,
        executionStore: ScheduledJobExecutionStore? = null
    ) = DynamicSchedulerService(
        store = store,
        taskScheduler = mockk(relaxed = true),
        mcpManager = mcpManager,
        slackMessageSender = slackSender,
        executionStore = executionStore
    )

    private class RecordingStore(private val job: ScheduledJob) : ScheduledJobStore {
        var lastStatus: JobExecutionStatus? = null
        var lastResult: String? = null

        override fun list(): List<ScheduledJob> = listOf(job)
        override fun findById(id: String): ScheduledJob? = if (id == job.id) job else null
        override fun findByName(name: String): ScheduledJob? = if (name == job.name) job else null
        override fun save(job: ScheduledJob): ScheduledJob = job
        override fun update(id: String, job: ScheduledJob): ScheduledJob? =
            if (id == this.job.id) job else null
        override fun delete(id: String) {}
        override fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?) {
            if (id == job.id && status != JobExecutionStatus.RUNNING) {
                lastStatus = status
                lastResult = result
            }
        }
    }

    private class RecordingExecutionStore : ScheduledJobExecutionStore {
        val saved = CopyOnWriteArrayList<ScheduledJobExecution>()

        override fun save(execution: ScheduledJobExecution): ScheduledJobExecution {
            val withId = if (execution.id.isBlank()) execution.copy(id = "exec-${saved.size + 1}") else execution
            saved.add(withId)
            return withId
        }

        override fun findByJobId(jobId: String, limit: Int): List<ScheduledJobExecution> =
            saved.filter { it.jobId == jobId }.take(limit)

        override fun findRecent(limit: Int): List<ScheduledJobExecution> = saved.take(limit)
    }
}
