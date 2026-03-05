package com.arc.reactor.slack

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.slack.handler.DefaultSlackEventHandler
import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.tool.ToolCallback
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultSlackEventHandlerProactiveTest {

    private val agentExecutor = mockk<AgentExecutor>()
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val mcpManager = mockk<McpManager>()
    private val tracker = SlackThreadTracker()

    private fun handler(mcpManager: McpManager? = this.mcpManager) = DefaultSlackEventHandler(
        agentExecutor = agentExecutor,
        messagingService = messagingService,
        threadTracker = tracker,
        mcpManager = mcpManager
    )

    private fun channelMessage(
        text: String = "How do I reset my password?",
        channelId: String = "C123",
        ts: String = "1000.001"
    ) = SlackEventCommand(
        eventType = "message",
        userId = "U456",
        channelId = channelId,
        text = text,
        ts = ts,
        threadTs = null
    )

    private fun setupMcpManager(tools: Map<String, List<String>> = emptyMap()) {
        val servers = tools.map { (name, _) ->
            McpServer(name = name, transportType = McpTransportType.SSE, config = emptyMap())
        }
        every { mcpManager.listServers() } returns servers
        for ((name, toolNames) in tools) {
            every { mcpManager.getStatus(name) } returns McpServerStatus.CONNECTED
            every { mcpManager.getToolCallbacks(name) } returns toolNames.map { toolName ->
                mockk<ToolCallback> { every { this@mockk.name } returns toolName }
            }
        }
    }

    @Nested
    inner class ProactiveChannelMessage {

        @Test
        fun `returns false and does not respond when agent returns NO_RESPONSE`() = runTest {
            setupMcpManager()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "[NO_RESPONSE]")

            val result = handler().handleChannelMessage(channelMessage())

            result shouldBe false
            coVerify(exactly = 0) { messagingService.sendMessage(any(), any(), any()) }
        }

        @Test
        fun `returns false for blank agent response`() = runTest {
            setupMcpManager()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "   ")

            val result = handler().handleChannelMessage(channelMessage())

            result shouldBe false
        }

        @Test
        fun `returns false for failed agent result`() = runTest {
            setupMcpManager()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = false, content = null, errorMessage = "LLM error")

            val result = handler().handleChannelMessage(channelMessage())

            result shouldBe false
        }

        @Test
        fun `returns true and sends response when agent provides useful content`() = runTest {
            setupMcpManager()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "You can reset your password at settings page.")
            coEvery { messagingService.sendMessage(any(), any(), any()) } returns
                SlackApiResult(ok = true)

            val result = handler().handleChannelMessage(channelMessage(ts = "2000.001"))

            result shouldBe true
            coVerify {
                messagingService.sendMessage(
                    channelId = "C123",
                    text = "You can reset your password at settings page.",
                    threadTs = "2000.001"
                )
            }
        }

        @Test
        fun `tracks thread after successful proactive response`() = runTest {
            setupMcpManager()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "Here's the info.")
            coEvery { messagingService.sendMessage(any(), any(), any()) } returns
                SlackApiResult(ok = true)

            handler().handleChannelMessage(channelMessage(channelId = "C999", ts = "3000.001"))

            tracker.isTracked("C999", "3000.001") shouldBe true
        }

        @Test
        fun `does not track thread when send fails`() = runTest {
            setupMcpManager()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "Here's the info.")
            coEvery { messagingService.sendMessage(any(), any(), any()) } returns
                SlackApiResult(ok = false, error = "not_in_channel")

            handler().handleChannelMessage(channelMessage(channelId = "C999", ts = "4000.001"))

            tracker.isTracked("C999", "4000.001") shouldBe false
        }

        @Test
        fun `returns false for blank input text`() = runTest {
            val result = handler().handleChannelMessage(channelMessage(text = "   "))

            result shouldBe false
            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }

        @Test
        fun `returns false and does not crash on agent exception`() = runTest {
            setupMcpManager()
            coEvery { agentExecutor.execute(any<AgentCommand>()) } throws RuntimeException("LLM down")

            val result = handler().handleChannelMessage(channelMessage())

            result shouldBe false
        }
    }

    @Nested
    inner class ProactiveSystemPrompt {

        @Test
        fun `uses proactive system prompt with NO_RESPONSE instruction`() = runTest {
            setupMcpManager()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "[NO_RESPONSE]")

            handler().handleChannelMessage(channelMessage())

            commandSlot.captured.systemPrompt shouldContain "[Proactive Assistance Mode]"
            commandSlot.captured.systemPrompt shouldContain "[NO_RESPONSE]"
        }

        @Test
        fun `includes proactive entrypoint in metadata`() = runTest {
            setupMcpManager()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "[NO_RESPONSE]")

            handler().handleChannelMessage(channelMessage())

            commandSlot.captured.metadata["entrypoint"] shouldBe "proactive"
        }

        @Test
        fun `uses proactive session ID format`() = runTest {
            setupMcpManager()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "[NO_RESPONSE]")

            handler().handleChannelMessage(channelMessage(channelId = "C789", ts = "5000.001"))

            val sessionId = commandSlot.captured.metadata["sessionId"] as String
            sessionId shouldBe "slack-proactive-C789-5000.001"
        }
    }

    @Nested
    inner class CrossToolPromptInjection {

        @Test
        fun `includes MCP tool summary in system prompt for mentions`() = runTest {
            setupMcpManager(
                mapOf("atlassian" to listOf("jira_search", "confluence_search"))
            )
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            handler().handleAppMention(
                SlackEventCommand("app_mention", "U1", "C1", "<@BOT> status?", "1.0", null)
            )

            commandSlot.captured.systemPrompt shouldContain "[Cross-tool Correlation]"
            commandSlot.captured.systemPrompt shouldContain "atlassian: jira_search, confluence_search"
        }

        @Test
        fun `omits cross-tool section when no MCP manager`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            handler(mcpManager = null).handleAppMention(
                SlackEventCommand("app_mention", "U1", "C1", "<@BOT> hello", "1.0", null)
            )

            commandSlot.captured.systemPrompt shouldNotContain "[Cross-tool Correlation]"
        }

        @Test
        fun `omits cross-tool section when no servers connected`() = runTest {
            every { mcpManager.listServers() } returns emptyList()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            handler().handleAppMention(
                SlackEventCommand("app_mention", "U1", "C1", "<@BOT> hello", "1.0", null)
            )

            commandSlot.captured.systemPrompt shouldNotContain "[Cross-tool Correlation]"
        }
    }
}
