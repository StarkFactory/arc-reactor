package com.arc.reactor.slack

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.controller.SlackEventController
import com.arc.reactor.slack.handler.DefaultSlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.proactive.InMemoryProactiveChannelStore
import com.arc.reactor.slack.processor.SlackEventProcessor
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.tool.ToolCallback
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * End-to-end scenario tests for Cross-tool Correlation and Proactive Agent features.
 *
 * Each test wires the full Slack event pipeline:
 * Controller → EventProcessor → EventHandler → AgentExecutor → MessagingService
 */
class SlackCrossToolAndProactiveE2ETest {

    private val objectMapper = jacksonObjectMapper()
    private val agentExecutor = mockk<AgentExecutor>()
    private val messagingService = mockk<SlackMessagingService>()
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)
    private val mcpManager = mockk<McpManager>()
    private val threadTracker = SlackThreadTracker()

    private fun setupMcpManager(servers: Map<String, List<String>>) {
        val mcpServers = servers.map { (name, _) ->
            McpServer(name = name, transportType = McpTransportType.SSE, config = emptyMap())
        }
        every { mcpManager.listServers() } returns mcpServers
        for ((name, toolNames) in servers) {
            every { mcpManager.getStatus(name) } returns McpServerStatus.CONNECTED
            every { mcpManager.getToolCallbacks(name) } returns toolNames.map { tn ->
                mockk<ToolCallback> { every { this@mockk.name } returns tn }
            }
        }
    }

    private val testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private fun buildPipeline(
        properties: SlackProperties = SlackProperties(enabled = true, maxConcurrentRequests = 5),
        mcpManager: McpManager? = this.mcpManager
    ): SlackEventController {
        val eventHandler = DefaultSlackEventHandler(
            agentExecutor = agentExecutor,
            messagingService = messagingService,
            threadTracker = threadTracker,
            mcpManager = mcpManager
        )
        val proactiveStore = InMemoryProactiveChannelStore()
        proactiveStore.seedFromConfig(properties.proactiveChannelIds)
        val eventProcessor = SlackEventProcessor(
            eventHandler = eventHandler,
            messagingService = messagingService,
            metricsRecorder = metricsRecorder,
            properties = properties,
            threadTracker = threadTracker,
            proactiveChannelStore = proactiveStore,
            scope = testScope
        )
        return SlackEventController(objectMapper, eventProcessor)
    }

    // =========================================================================
    // Cross-tool Correlation E2E
    // =========================================================================

    @Test
    fun `app_mention with connected MCP servers includes cross-tool prompt and returns combined answer`() = runTest {
        setupMcpManager(
            mapOf(
                "atlassian" to listOf("jira_search", "jira_get_issue", "confluence_search"),
                "github" to listOf("list_pull_requests", "get_commit")
            )
        )

        val commandSlot = slot<AgentCommand>()
        coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult(
            success = true,
            content = "Project ARC: 3 Jira tickets in progress, 2 PRs awaiting review, Confluence spec updated yesterday.",
            toolsUsed = listOf("jira_search", "list_pull_requests", "confluence_search")
        )
        coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(
            ok = true, ts = "2000.0002", channel = "C100"
        )

        val payload = """
            {
              "type": "event_callback",
              "event_id": "Ev-cross-001",
              "event": {
                "type": "app_mention",
                "user": "U200",
                "channel": "C100",
                "text": "<@BOT> 프로젝트 ARC 현황 알려줘",
                "ts": "2000.0001"
              }
            }
        """.trimIndent()

        val response = buildPipeline().handleEvent(payload)
        response.statusCode shouldBe HttpStatus.OK

        // Verify cross-tool prompt was injected
        coVerify(timeout = 3_000) {
            agentExecutor.execute(match { cmd ->
                cmd.systemPrompt.contains("[Cross-tool Correlation]") &&
                    cmd.systemPrompt.contains("atlassian: jira_search, jira_get_issue, confluence_search") &&
                    cmd.systemPrompt.contains("github: list_pull_requests, get_commit")
            })
        }

        // Verify combined answer was sent back
        coVerify(timeout = 3_000) {
            messagingService.sendMessage(
                "C100",
                match { it.contains("3 Jira tickets") && it.contains("2 PRs") },
                "2000.0001"
            )
        }

        commandSlot.captured.systemPrompt shouldContain "actively query ALL relevant tools"
        commandSlot.captured.metadata["sessionId"] shouldBe "slack-C100-2000.0001"
    }

    @Test
    fun `app_mention without MCP servers omits cross-tool prompt`() = runTest {
        every { mcpManager.listServers() } returns emptyList()

        val commandSlot = slot<AgentCommand>()
        coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult(
            success = true, content = "I can help with general questions."
        )
        coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(ok = true)

        val payload = """
            {
              "type": "event_callback",
              "event_id": "Ev-cross-002",
              "event": {
                "type": "app_mention",
                "user": "U200",
                "channel": "C100",
                "text": "<@BOT> hello",
                "ts": "3000.0001"
              }
            }
        """.trimIndent()

        buildPipeline().handleEvent(payload)

        coVerify(timeout = 3_000) { agentExecutor.execute(any<AgentCommand>()) }
        commandSlot.captured.systemPrompt shouldNotContain "[Cross-tool Correlation]"
    }

    // =========================================================================
    // Proactive Agent E2E
    // =========================================================================

    @Test
    fun `proactive agent responds in thread when LLM provides useful content`() = runTest {
        setupMcpManager(mapOf("atlassian" to listOf("jira_search")))

        val commandSlot = slot<AgentCommand>()
        coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult(
            success = true,
            content = ":mag: I found related ticket ARC-456 in Jira — it covers the deployment process.",
            toolsUsed = listOf("jira_search")
        )
        coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(
            ok = true, ts = "4000.0002", channel = "C_WATCH"
        )

        val properties = SlackProperties(
            enabled = true,
            maxConcurrentRequests = 5,
            proactiveEnabled = true,
            proactiveChannelIds = listOf("C_WATCH")
        )

        val payload = """
            {
              "type": "event_callback",
              "event_id": "Ev-proactive-001",
              "event": {
                "type": "message",
                "user": "U300",
                "channel": "C_WATCH",
                "text": "How do we deploy to staging? I keep getting errors.",
                "ts": "4000.0001"
              }
            }
        """.trimIndent()

        val response = buildPipeline(properties).handleEvent(payload)
        response.statusCode shouldBe HttpStatus.OK

        coVerify(timeout = 3_000) {
            messagingService.sendMessage(
                "C_WATCH",
                match { it.contains("ARC-456") },
                "4000.0001"
            )
        }

        // Verify proactive prompt was used
        commandSlot.captured.systemPrompt shouldContain "[Proactive Assistance Mode]"
        commandSlot.captured.systemPrompt shouldContain "[NO_RESPONSE]"
        commandSlot.captured.metadata["entrypoint"] shouldBe "proactive"
        commandSlot.captured.metadata["sessionId"] shouldBe "slack-proactive-C_WATCH-4000.0001"

        // Verify thread is tracked for follow-ups
        threadTracker.isTracked("C_WATCH", "4000.0001") shouldBe true
    }

    @Test
    fun `proactive agent stays silent when LLM returns NO_RESPONSE`() = runTest {
        setupMcpManager(mapOf("atlassian" to listOf("jira_search")))

        coEvery { agentExecutor.execute(any<AgentCommand>()) } returns AgentResult(
            success = true,
            content = "[NO_RESPONSE]"
        )

        val properties = SlackProperties(
            enabled = true,
            maxConcurrentRequests = 5,
            proactiveEnabled = true,
            proactiveChannelIds = listOf("C_WATCH")
        )

        val payload = """
            {
              "type": "event_callback",
              "event_id": "Ev-proactive-002",
              "event": {
                "type": "message",
                "user": "U300",
                "channel": "C_WATCH",
                "text": "Good morning everyone!",
                "ts": "5000.0001"
              }
            }
        """.trimIndent()

        val response = buildPipeline(properties).handleEvent(payload)
        response.statusCode shouldBe HttpStatus.OK

        // Unconfined scope makes processing synchronous — no sleep needed
        coVerify(exactly = 0) { messagingService.sendMessage(any(), any(), any()) }
        threadTracker.isTracked("C_WATCH", "5000.0001") shouldBe false
    }

    @Test
    fun `proactive agent ignores non-allowlisted channel`() = runTest {
        val properties = SlackProperties(
            enabled = true,
            maxConcurrentRequests = 5,
            proactiveEnabled = true,
            proactiveChannelIds = listOf("C_WATCH")
        )

        val payload = """
            {
              "type": "event_callback",
              "event_id": "Ev-proactive-003",
              "event": {
                "type": "message",
                "user": "U300",
                "channel": "C_OTHER",
                "text": "Why is the build failing?",
                "ts": "6000.0001"
              }
            }
        """.trimIndent()

        val response = buildPipeline(properties).handleEvent(payload)
        response.statusCode shouldBe HttpStatus.OK

        coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
    }

    @Test
    fun `proactive and mention coexist - mention still works in proactive channel`() = runTest {
        setupMcpManager(mapOf("atlassian" to listOf("jira_search")))

        val commandSlot = slot<AgentCommand>()
        coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult(
            success = true,
            content = "Here's the ticket status for ARC-789."
        )
        coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(ok = true)

        val properties = SlackProperties(
            enabled = true,
            maxConcurrentRequests = 5,
            proactiveEnabled = true,
            proactiveChannelIds = listOf("C_WATCH")
        )

        val payload = """
            {
              "type": "event_callback",
              "event_id": "Ev-proactive-004",
              "event": {
                "type": "app_mention",
                "user": "U300",
                "channel": "C_WATCH",
                "text": "<@BOT> ARC-789 상태 알려줘",
                "ts": "7000.0001"
              }
            }
        """.trimIndent()

        val response = buildPipeline(properties).handleEvent(payload)
        response.statusCode shouldBe HttpStatus.OK

        coVerify(timeout = 3_000) { agentExecutor.execute(any<AgentCommand>()) }

        // Mention uses regular prompt, not proactive
        commandSlot.captured.systemPrompt shouldNotContain "[Proactive Assistance Mode]"
        commandSlot.captured.systemPrompt shouldContain "[Cross-tool Correlation]"
        commandSlot.captured.metadata["entrypoint"] shouldBe null
    }
}
