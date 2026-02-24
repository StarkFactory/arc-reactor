package com.arc.reactor.scheduler

import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalResponse
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.hook.BeforeToolCallHook
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler

class DynamicSchedulerServicePolicyPipelineTest {

    @Test
    fun `trigger blocks when before tool hook rejects`() {
        val job = sampleJob()
        val store = RecordingStore(job)
        val taskScheduler = mockk<TaskScheduler>(relaxed = true)
        val mcpManager = mockk<McpManager>()
        val tool = mockk<ToolCallback>()

        every { tool.name } returns "danger_tool"
        coEvery { tool.call(any()) } returns "ok"
        coEvery { mcpManager.ensureConnected(job.mcpServerName) } returns true
        every { mcpManager.getToolCallbacks(job.mcpServerName) } returns listOf(tool)

        val hookExecutor = HookExecutor(
            beforeToolCallHooks = listOf(object : BeforeToolCallHook {
                override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
                    return HookResult.Reject("blocked by policy")
                }
            })
        )

        val service = DynamicSchedulerService(
            store = store,
            taskScheduler = taskScheduler,
            mcpManager = mcpManager,
            hookExecutor = hookExecutor
        )

        val result = service.trigger(job.id)

        assertTrue(result.contains("blocked by policy"))
        assertEquals(JobExecutionStatus.FAILED, store.lastStatus)
        coVerify(exactly = 0) { tool.call(any()) }
    }

    @Test
    fun `trigger waits approval and blocks when rejected`() {
        val job = sampleJob()
        val store = RecordingStore(job)
        val taskScheduler = mockk<TaskScheduler>(relaxed = true)
        val mcpManager = mockk<McpManager>()
        val tool = mockk<ToolCallback>()
        val approvalPolicy = mockk<ToolApprovalPolicy>()
        val pendingApprovalStore = mockk<PendingApprovalStore>()

        every { tool.name } returns "danger_tool"
        coEvery { tool.call(any()) } returns "ok"
        coEvery { mcpManager.ensureConnected(job.mcpServerName) } returns true
        every { mcpManager.getToolCallbacks(job.mcpServerName) } returns listOf(tool)
        every { approvalPolicy.requiresApproval("danger_tool", any()) } returns true
        coEvery {
            pendingApprovalStore.requestApproval(
                runId = any(),
                userId = any(),
                toolName = any(),
                arguments = any(),
                timeoutMs = any()
            )
        } returns ToolApprovalResponse(approved = false, reason = "manual reject")

        val service = DynamicSchedulerService(
            store = store,
            taskScheduler = taskScheduler,
            mcpManager = mcpManager,
            toolApprovalPolicy = approvalPolicy,
            pendingApprovalStore = pendingApprovalStore
        )

        val result = service.trigger(job.id)

        assertTrue(result.contains("rejected by human"))
        assertEquals(JobExecutionStatus.FAILED, store.lastStatus)
        coVerify(exactly = 1) {
            pendingApprovalStore.requestApproval(
                runId = any(),
                userId = any(),
                toolName = "danger_tool",
                arguments = any(),
                timeoutMs = any()
            )
        }
        coVerify(exactly = 0) { tool.call(any()) }
    }

    @Test
    fun `trigger uses modified arguments after approval`() {
        val job = sampleJob(toolArguments = mapOf("q" to "original"))
        val store = RecordingStore(job)
        val taskScheduler = mockk<TaskScheduler>(relaxed = true)
        val mcpManager = mockk<McpManager>()
        val tool = mockk<ToolCallback>()
        val approvalPolicy = mockk<ToolApprovalPolicy>()
        val pendingApprovalStore = mockk<PendingApprovalStore>()

        every { tool.name } returns "danger_tool"
        coEvery { mcpManager.ensureConnected(job.mcpServerName) } returns true
        every { mcpManager.getToolCallbacks(job.mcpServerName) } returns listOf(tool)
        every { approvalPolicy.requiresApproval("danger_tool", any()) } returns true
        coEvery {
            pendingApprovalStore.requestApproval(
                runId = any(),
                userId = any(),
                toolName = any(),
                arguments = any(),
                timeoutMs = any()
            )
        } returns ToolApprovalResponse(
            approved = true,
            modifiedArguments = mapOf("q" to "approved")
        )
        coEvery { tool.call(match { it["q"] == "approved" }) } returns "ok-approved"

        val service = DynamicSchedulerService(
            store = store,
            taskScheduler = taskScheduler,
            mcpManager = mcpManager,
            toolApprovalPolicy = approvalPolicy,
            pendingApprovalStore = pendingApprovalStore
        )

        val result = service.trigger(job.id)

        assertEquals("ok-approved", result)
        assertEquals(JobExecutionStatus.SUCCESS, store.lastStatus)
        coVerify(exactly = 1) { tool.call(match { it["q"] == "approved" }) }
    }

    private fun sampleJob(toolArguments: Map<String, Any> = emptyMap()): ScheduledJob {
        return ScheduledJob(
            id = "job-1",
            name = "daily-job",
            cronExpression = "0 0 * * * *",
            mcpServerName = "server-a",
            toolName = "danger_tool",
            toolArguments = toolArguments,
            enabled = true
        )
    }

    private class RecordingStore(
        private val job: ScheduledJob
    ) : ScheduledJobStore {
        var lastStatus: JobExecutionStatus? = null
        var lastResult: String? = null

        override fun list(): List<ScheduledJob> = listOf(job)

        override fun findById(id: String): ScheduledJob? = if (id == job.id) job else null

        override fun findByName(name: String): ScheduledJob? = if (name == job.name) job else null

        override fun save(job: ScheduledJob): ScheduledJob = job

        override fun update(id: String, job: ScheduledJob): ScheduledJob? = if (id == this.job.id) job else null

        override fun delete(id: String) {}

        override fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?) {
            if (id == job.id) {
                lastStatus = status
                lastResult = result
            }
        }
    }
}
