package com.arc.reactor.scheduler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.persona.Persona
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler
import java.time.Instant

class DynamicSchedulerServiceAgentExecutionTest {

    // ── AGENT mode tests ──────────────────────────────────────────────────────

    @Nested
    inner class AgentMode {

        @Test
        fun `AGENT job executes via AgentExecutor and records success`() {
            val job = agentJob()
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.success("오늘 스프린트 2개 완료, PR 3개 오픈 중입니다.")

            val service = buildService(store, agentExecutor = agentExecutor)

            val result = service.trigger(job.id)

            assertEquals("오늘 스프린트 2개 완료, PR 3개 오픈 중입니다.", result,
                "Trigger should return the agent's content")
            assertEquals(JobExecutionStatus.SUCCESS, store.lastStatus,
                "Job status should be SUCCESS after agent execution")
        }

        @Test
        fun `AGENT job passes correct AgentCommand to executor`() {
            val job = agentJob(
                agentPrompt = "브리핑해줘",
                agentModel = "gemini-2.5-pro",
                agentMaxToolCalls = 5
            )
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("결과")

            val service = buildService(store, agentExecutor = agentExecutor)
            service.trigger(job.id)

            val captured = commandSlot.captured
            assertEquals("브리핑해줘", captured.userPrompt,
                "AgentCommand.userPrompt should match job.agentPrompt")
            assertEquals("gemini-2.5-pro", captured.model,
                "AgentCommand.model should match job.agentModel")
            assertEquals(5, captured.maxToolCalls,
                "AgentCommand.maxToolCalls should match job.agentMaxToolCalls")
            assertEquals("scheduler", captured.userId,
                "AgentCommand.userId should be scheduler actor")
        }

        @Test
        fun `AGENT job sends natural language result to Slack without code block`() {
            val job = agentJob(slackChannelId = "C-BRIEFING")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.success("오늘 브리핑입니다.")

            val sentMessages = mutableListOf<Pair<String, String>>()
            val slackSender = SlackMessageSender { channelId, text ->
                sentMessages += channelId to text
            }

            val service = buildService(store, agentExecutor = agentExecutor, slackSender = slackSender)
            service.trigger(job.id)

            assertEquals(1, sentMessages.size, "Exactly one Slack message should be sent")
            val (channel, text) = sentMessages[0]
            assertEquals("C-BRIEFING", channel, "Message should go to the configured channel")
            assertTrue(text.contains("오늘 브리핑입니다."), "Slack message should contain agent result")
            assertTrue(!text.startsWith("```"), "AGENT mode should not wrap result in code block")
        }

        @Test
        fun `AGENT job fails when AgentExecutor is not available`() {
            val job = agentJob()
            val store = RecordingStore(job)

            val service = buildService(store, agentExecutor = null)
            val result = service.trigger(job.id)

            assertTrue(result.contains("AgentExecutor not available", ignoreCase = true),
                "Result should explain that AgentExecutor is unavailable")
            assertEquals(JobExecutionStatus.FAILED, store.lastStatus,
                "Job status should be FAILED when executor is missing")
        }

        @Test
        fun `AGENT job fails when agentPrompt is blank`() {
            val job = agentJob(agentPrompt = null)
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()

            val service = buildService(store, agentExecutor = agentExecutor)
            val result = service.trigger(job.id)

            assertTrue(result.contains("agentPrompt", ignoreCase = true),
                "Result should mention missing agentPrompt")
            assertEquals(JobExecutionStatus.FAILED, store.lastStatus,
                "Job status should be FAILED when agentPrompt is missing")
            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }

        @Test
        fun `AGENT job fails when AgentExecutor returns failure`() {
            val job = agentJob()
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.failure("LLM timeout")

            val service = buildService(store, agentExecutor = agentExecutor)
            val result = service.trigger(job.id)

            assertTrue(result.contains("LLM timeout", ignoreCase = true),
                "Result should propagate the agent's error message")
            assertEquals(JobExecutionStatus.FAILED, store.lastStatus,
                "Job status should be FAILED when agent execution fails")
        }
    }

    // ── System prompt resolution ──────────────────────────────────────────────

    @Nested
    inner class SystemPromptResolution {

        @Test
        fun `AGENT job resolves system prompt from personaId via PersonaStore`() {
            val job = agentJob(personaId = "briefing-assistant")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val personaStore = mockk<PersonaStore>()
            val commandSlot = slot<AgentCommand>()

            every { personaStore.get("briefing-assistant") } returns
                Persona(id = "briefing-assistant", name = "Briefing", systemPrompt = "너는 회사 비서야.")
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor, personaStore = personaStore)
            service.trigger(job.id)

            assertEquals("너는 회사 비서야.", commandSlot.captured.systemPrompt,
                "systemPrompt should come from the resolved persona")
        }

        @Test
        fun `AGENT job prefers agentSystemPrompt over personaId`() {
            val job = agentJob(
                personaId = "briefing-assistant",
                agentSystemPrompt = "직접 지정한 시스템 프롬프트"
            )
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val personaStore = mockk<PersonaStore>()
            val commandSlot = slot<AgentCommand>()

            every { personaStore.get(any()) } returns
                Persona(id = "briefing-assistant", name = "Briefing", systemPrompt = "페르소나 프롬프트")
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor, personaStore = personaStore)
            service.trigger(job.id)

            assertEquals("직접 지정한 시스템 프롬프트", commandSlot.captured.systemPrompt,
                "agentSystemPrompt should take precedence over personaId")
        }

        @Test
        fun `AGENT job falls back to default persona when no systemPrompt configured`() {
            val job = agentJob(personaId = null, agentSystemPrompt = null)
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val personaStore = mockk<PersonaStore>()
            val commandSlot = slot<AgentCommand>()

            every { personaStore.getDefault() } returns
                Persona(id = "default", name = "Default", systemPrompt = "기본 어시스턴트입니다.", isDefault = true)
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor, personaStore = personaStore)
            service.trigger(job.id)

            assertEquals("기본 어시스턴트입니다.", commandSlot.captured.systemPrompt,
                "Default persona systemPrompt should be used as fallback")
        }

        @Test
        fun `AGENT job falls back to hardcoded prompt when PersonaStore is unavailable`() {
            val job = agentJob(personaId = null, agentSystemPrompt = null)
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val commandSlot = slot<AgentCommand>()

            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor, personaStore = null)
            service.trigger(job.id)

            assertEquals("You are a helpful AI assistant.", commandSlot.captured.systemPrompt,
                "Hardcoded fallback should be used when PersonaStore is absent")
        }
    }

    // ── MCP_TOOL regression ───────────────────────────────────────────────────

    @Nested
    inner class McpToolRegression {

        @Test
        fun `MCP_TOOL job still executes correctly after AGENT mode addition`() {
            val job = ScheduledJob(
                id = "mcp-job-1",
                name = "legacy-mcp-job",
                cronExpression = "0 0 9 * * *",
                jobType = ScheduledJobType.MCP_TOOL,
                mcpServerName = "atlassian",
                toolName = "jira_search_issues",
                toolArguments = mapOf("jql" to "sprint in openSprints()"),
                enabled = true
            )
            val store = RecordingStore(job)
            val mcpManager = mockk<McpManager>()
            val tool = mockk<ToolCallback>()

            every { tool.name } returns "jira_search_issues"
            coEvery { tool.call(any()) } returns """{"issues": []}"""
            coEvery { mcpManager.ensureConnected("atlassian") } returns true
            every { mcpManager.getToolCallbacks("atlassian") } returns listOf(tool)

            val service = buildService(store, mcpManager = mcpManager)
            val result = service.trigger(job.id)

            assertEquals("""{"issues": []}""", result,
                "MCP_TOOL job should return raw tool output")
            assertEquals(JobExecutionStatus.SUCCESS, store.lastStatus,
                "MCP_TOOL job status should be SUCCESS")
            coVerify(exactly = 1) { tool.call(any()) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun agentJob(
        agentPrompt: String? = "오늘 아침 브리핑해줘",
        personaId: String? = null,
        agentSystemPrompt: String? = null,
        agentModel: String? = null,
        agentMaxToolCalls: Int? = null,
        slackChannelId: String? = null
    ) = ScheduledJob(
        id = "agent-job-1",
        name = "morning-briefing",
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.AGENT,
        agentPrompt = agentPrompt,
        personaId = personaId,
        agentSystemPrompt = agentSystemPrompt,
        agentModel = agentModel,
        agentMaxToolCalls = agentMaxToolCalls,
        slackChannelId = slackChannelId,
        enabled = true
    )

    private fun buildService(
        store: ScheduledJobStore,
        mcpManager: McpManager = mockk(relaxed = true),
        agentExecutor: AgentExecutor? = null,
        personaStore: PersonaStore? = null,
        slackSender: SlackMessageSender? = null
    ) = DynamicSchedulerService(
        store = store,
        taskScheduler = mockk(relaxed = true),
        mcpManager = mcpManager,
        slackMessageSender = slackSender,
        agentExecutor = agentExecutor,
        personaStore = personaStore
    )

    private class RecordingStore(private val job: ScheduledJob) : ScheduledJobStore {
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
