package com.arc.reactor.scheduler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.SchedulerProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.tool.ToolCallback
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DynamicSchedulerService 커버리지 보강 테스트.
 *
 * 기존 테스트 스위트에서 미검증된 경계 케이스를 보완한다:
 * - Slack/Teams 메시지 포맷 (MCP_TOOL vs AGENT 코드블록 차이)
 * - 메시지 잘림(truncation) 동작
 * - afterToolCall 훅 실패 경로
 * - agentExecutorProvider가 null을 반환하는 케이스
 * - executionTimeoutMs=0 실제 실행 동작 (무한 실행, 타임아웃 없음)
 * - 실행 이력 completedAt 필드
 */
class DynamicSchedulerServiceCoverageGapTest {

    // ── Slack 메시지 포맷 ──────────────────────────────────────────────────────

    @Nested
    inner class SlackMessageFormat {

        @Test
        fun `MCP_TOOL 작업 성공 시 Slack 메시지는 코드블록으로 감싼다`() {
            val job = mcpJob(slackChannelId = "C-TOOL")
            val store = RecordingStore(job)
            val sent = CopyOnWriteArrayList<Pair<String, String>>()
            val slackSender = SlackMessageSender { ch, txt -> sent += ch to txt }
            val mcpManager = mockMcpManager(job, "tool output result")

            buildService(store, mcpManager = mcpManager, slackSender = slackSender)
                .trigger(job.id)

            sent shouldHaveSize 1 withMessage "Slack 메시지가 정확히 1건 전송되어야 한다"
            val text = sent[0].second
            text shouldContain "```" withMessage "MCP_TOOL 결과는 코드블록으로 감싸야 한다"
            text shouldContain "tool output result" withMessage "메시지에 결과 내용이 포함되어야 한다"
            text shouldContain "[${job.name}]" withMessage "메시지에 작업 이름이 포함되어야 한다"
        }

        @Test
        fun `AGENT 작업 성공 시 Slack 메시지는 코드블록 없이 전송된다`() {
            val job = agentJob(slackChannelId = "C-AGENT")
            val store = RecordingStore(job)
            val sent = CopyOnWriteArrayList<Pair<String, String>>()
            val slackSender = SlackMessageSender { ch, txt -> sent += ch to txt }
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.success("에이전트 실행 결과")

            buildService(store, agentExecutor = agentExecutor, slackSender = slackSender)
                .trigger(job.id)

            sent shouldHaveSize 1 withMessage "Slack 메시지가 정확히 1건 전송되어야 한다"
            val text = sent[0].second
            text shouldNotContain "```" withMessage "AGENT 결과는 코드블록을 사용하지 않아야 한다"
            text shouldContain "에이전트 실행 결과" withMessage "에이전트 결과가 포함되어야 한다"
        }
    }

    // ── Teams 메시지 포맷 ──────────────────────────────────────────────────────

    @Nested
    inner class TeamsMessageFormat {

        @Test
        fun `MCP_TOOL 작업 성공 시 Teams 메시지는 볼드 대괄호와 코드블록을 사용한다`() {
            val job = mcpJob(teamsWebhookUrl = "https://teams.hook/1")
            val store = RecordingStore(job)
            val sent = CopyOnWriteArrayList<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { url, txt -> sent += url to txt }
            val mcpManager = mockMcpManager(job, "mcp result")

            buildService(store, mcpManager = mcpManager, teamsSender = teamsSender)
                .trigger(job.id)

            sent shouldHaveSize 1 withMessage "Teams 메시지가 정확히 1건 전송되어야 한다"
            val text = sent[0].second
            text shouldContain "**[${job.name}]**" withMessage "MCP_TOOL 메시지는 볼드 대괄호를 사용해야 한다"
            text shouldContain "```" withMessage "MCP_TOOL 메시지는 코드블록을 포함해야 한다"
            text shouldContain "mcp result" withMessage "메시지에 결과 내용이 포함되어야 한다"
        }
    }

    // ── 메시지 잘림 ───────────────────────────────────────────────────────────

    @Nested
    inner class MessageTruncation {

        @Test
        fun `3000자 초과 결과는 Slack 메시지에서 잘리고 줄임표가 추가된다`() {
            val longResult = "X".repeat(3100)
            val job = mcpJob(slackChannelId = "C-TRUNC")
            val store = RecordingStore(job)
            val sent = CopyOnWriteArrayList<Pair<String, String>>()
            val slackSender = SlackMessageSender { _, txt -> sent += "" to txt }
            val mcpManager = mockMcpManager(job, longResult)

            buildService(store, mcpManager = mcpManager, slackSender = slackSender)
                .trigger(job.id)

            sent shouldHaveSize 1 withMessage "Slack 메시지가 전송되어야 한다"
            val text = sent[0].second
            text shouldContain "..." withMessage "잘린 메시지에는 줄임표가 추가되어야 한다"
            // 원본 3100자가 그대로 포함되지 않아야 한다
            (text.length < longResult.length) shouldBe true withMessage "Slack 메시지는 원본보다 짧아야 한다"
        }

        @Test
        fun `3000자 이하 결과는 Teams 메시지에서 잘리지 않는다`() {
            val result = "A".repeat(100)
            val job = mcpJob(teamsWebhookUrl = "https://teams.hook/2")
            val store = RecordingStore(job)
            val sent = CopyOnWriteArrayList<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { _, txt -> sent += "" to txt }
            val mcpManager = mockMcpManager(job, result)

            buildService(store, mcpManager = mcpManager, teamsSender = teamsSender)
                .trigger(job.id)

            sent shouldHaveSize 1 withMessage "Teams 메시지가 전송되어야 한다"
            sent[0].second shouldContain result withMessage "짧은 결과는 잘리지 않아야 한다"
        }

        @Test
        fun `3000자 초과 결과는 Teams 메시지에서도 잘린다`() {
            val longResult = "Y".repeat(3500)
            val job = agentJob(teamsWebhookUrl = "https://teams.hook/3")
            val store = RecordingStore(job)
            val sent = CopyOnWriteArrayList<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { _, txt -> sent += "" to txt }
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns AgentResult.success(longResult)

            buildService(store, agentExecutor = agentExecutor, teamsSender = teamsSender)
                .trigger(job.id)

            sent shouldHaveSize 1 withMessage "Teams 메시지가 전송되어야 한다"
            val text = sent[0].second
            text shouldContain "..." withMessage "잘린 Teams 메시지에는 줄임표가 추가되어야 한다"
        }
    }

    // ── afterToolCall 훅 실패 경로 ────────────────────────────────────────────

    @Nested
    inner class AfterToolCallHookOnFailure {

        @Test
        fun `도구 실행 실패 시 afterToolCall 훅이 success=false로 호출된다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val hookExecutor = mockk<HookExecutor>()
            val capturedResults = CopyOnWriteArrayList<ToolCallResult>()

            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } answers {
                capturedResults += secondArg<ToolCallResult>()
            }

            val tool = mockk<ToolCallback>()
            val mcpManager = mockk<McpManager>()
            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } throws RuntimeException("tool exploded")
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)

            buildService(store, mcpManager = mcpManager, hookExecutor = hookExecutor)
                .trigger(job.id)

            coVerify(exactly = 1) { hookExecutor.executeAfterToolCall(any(), any()) }
            capturedResults shouldHaveSize 1 withMessage "afterToolCall이 1번 호출되어야 한다"
            capturedResults[0].success shouldBe false withMessage "도구 실패 시 success=false로 전달되어야 한다"
            capturedResults[0].errorMessage.shouldNotBeNull() withMessage "실패 시 errorMessage가 설정되어야 한다"
        }

        @Test
        fun `도구 실행 성공 시 afterToolCall 훅이 success=true로 호출된다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val hookExecutor = mockk<HookExecutor>()
            val capturedResults = CopyOnWriteArrayList<ToolCallResult>()

            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } answers {
                capturedResults += secondArg<ToolCallResult>()
            }
            val mcpManager = mockMcpManager(job, "tool output")

            buildService(store, mcpManager = mcpManager, hookExecutor = hookExecutor)
                .trigger(job.id)

            capturedResults shouldHaveSize 1 withMessage "afterToolCall이 1번 호출되어야 한다"
            capturedResults[0].success shouldBe true withMessage "도구 성공 시 success=true로 전달되어야 한다"
        }
    }

    // ── agentExecutorProvider null 반환 ───────────────────────────────────────

    @Nested
    inner class AgentExecutorProviderReturnsNull {

        @Test
        fun `agentExecutor=null이고 provider가 null을 반환하면 FAILED 상태`() {
            val job = agentJob()
            val store = RecordingStore(job)
            // executor=null, provider는 존재하지만 null을 반환
            val nullProvider: () -> AgentExecutor? = { null }

            val result = buildService(
                store,
                agentExecutor = null,
                agentExecutorProvider = nullProvider
            ).trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "provider가 null을 반환하면 FAILED 상태여야 한다"
            result shouldContain "IllegalStateException" withMessage
                "에러 메시지에 AgentExecutor 미가용 내용이 포함되어야 한다"
        }
    }

    // ── executionTimeoutMs=0 실제 실행 동작 ──────────────────────────────────

    @Nested
    inner class ZeroTimeoutMeansUnlimited {

        @Test
        fun `executionTimeoutMs=0인 작업은 타임아웃 없이 정상 실행된다`() {
            val job = mcpJob(executionTimeoutMs = 0L)
            val store = RecordingStore(job)
            val mcpManager = mockMcpManager(job, "unlimited result")

            val result = buildService(store, mcpManager = mcpManager)
                .trigger(job.id)

            result shouldBe "unlimited result" withMessage
                "executionTimeoutMs=0인 작업은 정상 결과를 반환해야 한다"
            store.lastStatus shouldBe JobExecutionStatus.SUCCESS withMessage
                "executionTimeoutMs=0이면 SUCCESS 상태여야 한다"
        }

        @Test
        fun `schedulerProperties의 defaultExecutionTimeoutMs가 0이면 기본 5분 타임아웃이 적용된다`() {
            // 짧게 완료되는 도구 호출 — 5분 기본값으로 정상 완료되어야 한다
            val job = mcpJob(executionTimeoutMs = null)
            val store = RecordingStore(job)
            val props = SchedulerProperties(defaultExecutionTimeoutMs = 0L)
            val mcpManager = mockMcpManager(job, "default timeout result")

            val result = buildService(store, mcpManager = mcpManager, schedulerProperties = props)
                .trigger(job.id)

            result shouldBe "default timeout result" withMessage
                "defaultExecutionTimeoutMs=0일 때도 정상 실행되어야 한다"
            store.lastStatus shouldBe JobExecutionStatus.SUCCESS withMessage
                "defaultExecutionTimeoutMs=0은 DEFAULT_JOB_TIMEOUT_MS로 폴백해야 한다"
        }
    }

    // ── 실행 이력 completedAt ─────────────────────────────────────────────────

    @Nested
    inner class ExecutionHistoryCompletedAt {

        @Test
        fun `실행 이력에 completedAt이 설정된다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockMcpManager(job, "result")

            buildService(store, mcpManager = mcpManager, executionStore = execStore)
                .trigger(job.id)

            execStore.saved shouldHaveSize 1 withMessage "실행 이력이 1건 기록되어야 한다"
            execStore.saved[0].completedAt.shouldNotBeNull() withMessage
                "실행 이력의 completedAt이 설정되어야 한다"
        }

        @Test
        fun `드라이런 이력에도 completedAt이 설정된다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockMcpManager(job, "dry result")

            buildService(store, mcpManager = mcpManager, executionStore = execStore)
                .dryRun(job.id)

            execStore.saved shouldHaveSize 1 withMessage "드라이런 이력이 1건 기록되어야 한다"
            execStore.saved[0].completedAt.shouldNotBeNull() withMessage
                "드라이런 이력의 completedAt이 설정되어야 한다"
            execStore.saved[0].dryRun shouldBe true withMessage
                "드라이런 이력의 dryRun 플래그가 true여야 한다"
        }

        @Test
        fun `실패 실행 이력에도 completedAt이 설정된다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val tool = mockk<ToolCallback>()
            val mcpManager = mockk<McpManager>()
            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } throws RuntimeException("tool failed")
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)

            buildService(store, mcpManager = mcpManager, executionStore = execStore)
                .trigger(job.id)

            execStore.saved shouldHaveSize 1 withMessage "실패 이력이 1건 기록되어야 한다"
            execStore.saved[0].completedAt.shouldNotBeNull() withMessage
                "실패 이력의 completedAt이 설정되어야 한다"
            execStore.saved[0].status shouldBe JobExecutionStatus.FAILED withMessage
                "실패 이력 상태가 FAILED여야 한다"
        }
    }

    // ── ToolCallContext 메타데이터 ─────────────────────────────────────────────

    @Nested
    inner class ToolCallContextMetadata {

        @Test
        fun `MCP_TOOL 실행 시 beforeToolCall에 전달되는 ToolCallContext에 도구명이 포함된다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val capturedContexts = CopyOnWriteArrayList<ToolCallContext>()
            val hookExecutor = mockk<HookExecutor>()
            coEvery { hookExecutor.executeBeforeToolCall(any()) } answers {
                capturedContexts += firstArg<ToolCallContext>()
                HookResult.Continue
            }
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } returns Unit
            val mcpManager = mockMcpManager(job, "ok")

            buildService(store, mcpManager = mcpManager, hookExecutor = hookExecutor)
                .trigger(job.id)

            capturedContexts shouldHaveSize 1 withMessage "beforeToolCall이 1번 호출되어야 한다"
            capturedContexts[0].toolName shouldBe "test_tool" withMessage
                "ToolCallContext에 도구명이 올바르게 포함되어야 한다"
        }

        @Test
        fun `MCP_TOOL 실행 시 beforeToolCall 컨텍스트의 userId가 scheduler이다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val capturedContexts = CopyOnWriteArrayList<ToolCallContext>()
            val hookExecutor = mockk<HookExecutor>()
            coEvery { hookExecutor.executeBeforeToolCall(any()) } answers {
                capturedContexts += firstArg<ToolCallContext>()
                HookResult.Continue
            }
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } returns Unit
            val mcpManager = mockMcpManager(job, "ok")

            buildService(store, mcpManager = mcpManager, hookExecutor = hookExecutor)
                .trigger(job.id)

            capturedContexts[0].agentContext.userId shouldBe "scheduler" withMessage
                "스케줄러 실행의 userId는 'scheduler'여야 한다"
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private infix fun <T> T.withMessage(@Suppress("UNUSED_PARAMETER") message: String): T = this

    private fun mcpJob(
        slackChannelId: String? = null,
        teamsWebhookUrl: String? = null,
        executionTimeoutMs: Long? = null
    ) = ScheduledJob(
        id = "job-1",
        name = "coverage-mcp-job",
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.MCP_TOOL,
        mcpServerName = "test-server",
        toolName = "test_tool",
        toolArguments = emptyMap(),
        slackChannelId = slackChannelId,
        teamsWebhookUrl = teamsWebhookUrl,
        executionTimeoutMs = executionTimeoutMs,
        enabled = true
    )

    private fun agentJob(
        slackChannelId: String? = null,
        teamsWebhookUrl: String? = null
    ) = ScheduledJob(
        id = "agent-1",
        name = "coverage-agent-job",
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.AGENT,
        agentPrompt = "오늘 브리핑해줘",
        slackChannelId = slackChannelId,
        teamsWebhookUrl = teamsWebhookUrl,
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
        taskScheduler: TaskScheduler = mockk(relaxed = true),
        mcpManager: McpManager = mockk(relaxed = true),
        slackSender: SlackMessageSender? = null,
        teamsSender: TeamsMessageSender? = null,
        hookExecutor: HookExecutor? = null,
        agentExecutor: AgentExecutor? = null,
        agentExecutorProvider: (() -> AgentExecutor?)? = null,
        executionStore: ScheduledJobExecutionStore? = null,
        schedulerProperties: SchedulerProperties = SchedulerProperties()
    ) = DynamicSchedulerService(
        store = store,
        taskScheduler = taskScheduler,
        mcpManager = mcpManager,
        slackMessageSender = slackSender,
        teamsMessageSender = teamsSender,
        hookExecutor = hookExecutor,
        agentExecutor = agentExecutor,
        agentExecutorProvider = agentExecutorProvider,
        executionStore = executionStore,
        schedulerProperties = schedulerProperties
    )

    private class RecordingStore(private val job: ScheduledJob) : ScheduledJobStore {
        var lastStatus: JobExecutionStatus? = null

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

        override fun deleteOldestExecutions(jobId: String, keepCount: Int) {}
    }
}
