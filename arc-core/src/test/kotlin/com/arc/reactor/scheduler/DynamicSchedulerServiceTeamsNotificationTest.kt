package com.arc.reactor.scheduler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.mcp.McpManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler

class DynamicSchedulerServiceTeamsNotificationTest {

    // ── Teams notification tests ──────────────────────────────────────────────

    @Nested
    inner class TeamsNotification {

        @Test
        fun `sends Teams message when teamsWebhookUrl is set on AGENT job`() {
            val job = agentJob(teamsWebhookUrl = "https://example.webhook.office.com/webhookb2/test")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.success("오늘 브리핑 결과입니다.")

            val sentMessages = mutableListOf<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { webhookUrl, text ->
                sentMessages += webhookUrl to text
            }

            val service = buildService(store, agentExecutor = agentExecutor, teamsSender = teamsSender)
            service.trigger(job.id)

            assertEquals(1, sentMessages.size, "Exactly one Teams message should be sent")
            val (url, text) = sentMessages[0]
            assertEquals("https://example.webhook.office.com/webhookb2/test", url,
                "Teams message must go to the configured webhook URL")
            assertTrue(text.contains("오늘 브리핑 결과입니다."),
                "Teams message must contain the agent's result")
        }

        @Test
        fun `does not send Teams message when teamsWebhookUrl is null`() {
            val job = agentJob(teamsWebhookUrl = null)
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.success("결과")

            val sentMessages = mutableListOf<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { webhookUrl, text ->
                sentMessages += webhookUrl to text
            }

            val service = buildService(store, agentExecutor = agentExecutor, teamsSender = teamsSender)
            service.trigger(job.id)

            assertEquals(0, sentMessages.size, "No Teams message should be sent when teamsWebhookUrl is null")
        }

        @Test
        fun `does not send Teams message when teamsWebhookUrl is blank`() {
            val job = agentJob(teamsWebhookUrl = "   ")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.success("결과")

            val sentMessages = mutableListOf<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { webhookUrl, text ->
                sentMessages += webhookUrl to text
            }

            val service = buildService(store, agentExecutor = agentExecutor, teamsSender = teamsSender)
            service.trigger(job.id)

            assertEquals(0, sentMessages.size, "No Teams message should be sent when teamsWebhookUrl is blank")
        }

        @Test
        fun `does not send Teams message when teamsMessageSender is not configured`() {
            val job = agentJob(teamsWebhookUrl = "https://example.webhook.office.com/webhookb2/test")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.success("결과")

            // no teamsSender wired
            val service = buildService(store, agentExecutor = agentExecutor, teamsSender = null)
            val result = service.trigger(job.id)

            assertEquals("결과", result, "Job should still succeed when TeamsMessageSender is absent")
        }

        @Test
        fun `Teams failure does not affect job execution result`() {
            val job = agentJob(teamsWebhookUrl = "https://example.webhook.office.com/webhookb2/test")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.success("결과")

            val brokenSender = TeamsMessageSender { _, _ ->
                throw RuntimeException("Teams webhook unreachable")
            }

            val service = buildService(store, agentExecutor = agentExecutor, teamsSender = brokenSender)
            val result = service.trigger(job.id)

            assertEquals("결과", result, "Job result should be unaffected by Teams notification failure")
            assertEquals(JobExecutionStatus.SUCCESS, store.lastStatus,
                "Job status should be SUCCESS even when Teams notification throws")
        }
    }

    // ── Slack + Teams co-existence ────────────────────────────────────────────

    @Nested
    inner class SlackAndTeamsCoexistence {

        @Test
        fun `both Slack and Teams messages are sent for the same job`() {
            val job = agentJob(
                slackChannelId = "C-CHANNEL",
                teamsWebhookUrl = "https://example.webhook.office.com/webhookb2/test"
            )
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.success("오늘 공동 브리핑입니다.")

            val slackMessages = mutableListOf<Pair<String, String>>()
            val slackSender = SlackMessageSender { channelId, text ->
                slackMessages += channelId to text
            }

            val teamsMessages = mutableListOf<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { webhookUrl, text ->
                teamsMessages += webhookUrl to text
            }

            val service = buildService(
                store,
                agentExecutor = agentExecutor,
                slackSender = slackSender,
                teamsSender = teamsSender
            )
            service.trigger(job.id)

            assertEquals(1, slackMessages.size, "Exactly one Slack message should be sent")
            assertEquals(1, teamsMessages.size, "Exactly one Teams message should be sent")
            assertEquals("C-CHANNEL", slackMessages[0].first,
                "Slack message must go to the configured channel")
            assertEquals("https://example.webhook.office.com/webhookb2/test", teamsMessages[0].first,
                "Teams message must go to the configured webhook URL")
            assertTrue(slackMessages[0].second.contains("오늘 공동 브리핑입니다."),
                "Slack message must contain the result")
            assertTrue(teamsMessages[0].second.contains("오늘 공동 브리핑입니다."),
                "Teams message must contain the result")
        }

        @Test
        fun `only Slack sent when teamsWebhookUrl is not set`() {
            val job = agentJob(slackChannelId = "C-ONLY-SLACK", teamsWebhookUrl = null)
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns AgentResult.success("결과")

            val slackMessages = mutableListOf<Pair<String, String>>()
            val slackSender = SlackMessageSender { channelId, text -> slackMessages += channelId to text }
            val teamsMessages = mutableListOf<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { webhookUrl, text -> teamsMessages += webhookUrl to text }

            val service = buildService(
                store, agentExecutor = agentExecutor,
                slackSender = slackSender, teamsSender = teamsSender
            )
            service.trigger(job.id)

            assertEquals(1, slackMessages.size, "One Slack message should be sent")
            assertEquals(0, teamsMessages.size, "No Teams message should be sent when URL is absent")
        }
    }

    // ── Teams message format ──────────────────────────────────────────────────

    @Nested
    inner class TeamsMessageFormat {

        @Test
        fun `MCP_TOOL job Teams message uses bold brackets and code block`() {
            val job = mcpToolJob(teamsWebhookUrl = "https://example.webhook.office.com/webhookb2/test")
            val store = RecordingStore(job)

            val sentMessages = mutableListOf<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { webhookUrl, text -> sentMessages += webhookUrl to text }

            val mcpManager = mockk<McpManager>(relaxed = true)
            val toolCallback = mockk<com.arc.reactor.tool.ToolCallback>()
            every { toolCallback.name } returns "get_weather"
            io.mockk.coEvery { toolCallback.call(any()) } returns "sunny"
            io.mockk.coEvery { mcpManager.ensureConnected("weather-server") } returns true
            every { mcpManager.getToolCallbacks("weather-server") } returns listOf(toolCallback)

            val service = buildService(
                store, mcpManager = mcpManager, teamsSender = teamsSender
            )
            service.trigger(job.id)

            assertEquals(1, sentMessages.size, "One Teams message should be sent for MCP_TOOL job")
            val text = sentMessages[0].second
            assertTrue(text.contains("**[${job.name}]**"),
                "MCP_TOOL Teams message must use bold brackets with job name")
        }

        @Test
        fun `AGENT job Teams message uses bold brackets without code block`() {
            val job = agentJob(teamsWebhookUrl = "https://example.webhook.office.com/webhookb2/test")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.success("에이전트 결과")

            val sentMessages = mutableListOf<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { webhookUrl, text -> sentMessages += webhookUrl to text }

            val service = buildService(
                store, agentExecutor = agentExecutor, teamsSender = teamsSender
            )
            service.trigger(job.id)

            assertEquals(1, sentMessages.size, "One Teams message should be sent for AGENT job")
            val text = sentMessages[0].second
            assertTrue(text.contains("**[${job.name}]** 브리핑:"),
                "AGENT Teams message must use bold brackets with 브리핑 label")
            assertTrue(text.contains("에이전트 결과"),
                "AGENT Teams message must contain the agent result")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun agentJob(
        slackChannelId: String? = null,
        teamsWebhookUrl: String? = null
    ) = ScheduledJob(
        id = "teams-agent-job-1",
        name = "morning-briefing",
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.AGENT,
        agentPrompt = "오늘 아침 브리핑해줘",
        slackChannelId = slackChannelId,
        teamsWebhookUrl = teamsWebhookUrl,
        enabled = true
    )

    private fun mcpToolJob(teamsWebhookUrl: String? = null) = ScheduledJob(
        id = "teams-mcp-job-1",
        name = "weather-check",
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.MCP_TOOL,
        mcpServerName = "weather-server",
        toolName = "get_weather",
        toolArguments = emptyMap(),
        teamsWebhookUrl = teamsWebhookUrl,
        enabled = true
    )

    private fun buildService(
        store: ScheduledJobStore,
        mcpManager: McpManager = mockk(relaxed = true),
        agentExecutor: AgentExecutor? = null,
        slackSender: SlackMessageSender? = null,
        teamsSender: TeamsMessageSender? = null
    ) = DynamicSchedulerService(
        store = store,
        taskScheduler = mockk(relaxed = true),
        mcpManager = mcpManager,
        slackMessageSender = slackSender,
        teamsMessageSender = teamsSender,
        agentExecutor = agentExecutor
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
