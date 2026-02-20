package com.arc.reactor.slack

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.controller.SlackCommandController
import com.arc.reactor.slack.controller.SlackEventController
import com.arc.reactor.slack.handler.DefaultSlackCommandHandler
import com.arc.reactor.slack.handler.DefaultSlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.processor.SlackCommandProcessor
import com.arc.reactor.slack.processor.SlackEventProcessor
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class SlackUserJourneyScenarioTest {

    private val objectMapper = jacksonObjectMapper()
    private val agentExecutor = mockk<AgentExecutor>()
    private val messagingService = mockk<SlackMessagingService>()
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)

    @Test
    fun `events api app mention should surface guard rejection to user thread`() = runTest {
        val eventHandler = DefaultSlackEventHandler(agentExecutor, messagingService)
        val eventProcessor = SlackEventProcessor(
            eventHandler = eventHandler,
            messagingService = messagingService,
            metricsRecorder = metricsRecorder,
            properties = SlackProperties(enabled = true, maxConcurrentRequests = 5)
        )
        val controller = SlackEventController(objectMapper, eventProcessor)

        val commandSlot = slot<AgentCommand>()
        coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult(
            success = false,
            content = null,
            errorCode = AgentErrorCode.GUARD_REJECTED,
            errorMessage = "Blocked by guard policy"
        )
        coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(
            ok = true,
            ts = "1710000.0002",
            channel = "C456"
        )

        val payload = """
            {
              "type": "event_callback",
              "event_id": "Ev-guard-001",
              "event": {
                "type": "app_mention",
                "user": "U123",
                "channel": "C456",
                "text": "<@BOT123> please run dangerous command",
                "ts": "1710000.0001"
              }
            }
        """.trimIndent()

        val response = controller.handleEvent(payload)
        response.statusCode shouldBe HttpStatus.OK

        coVerify(timeout = 2_000) {
            messagingService.sendMessage(
                "C456",
                match { it.contains(":warning:") && it.contains("Blocked by guard policy") },
                "1710000.0001"
            )
        }
        commandSlot.captured.userPrompt shouldBe "please run dangerous command"
        commandSlot.captured.metadata["sessionId"] shouldBe "slack-C456-1710000.0001"
        commandSlot.captured.metadata["source"] shouldBe "slack"
        commandSlot.captured.metadata["channel"] shouldBe "slack"
    }

    @Test
    fun `events api threaded message should return rag and mcp enriched answer`() = runTest {
        val eventHandler = DefaultSlackEventHandler(agentExecutor, messagingService)
        val eventProcessor = SlackEventProcessor(
            eventHandler = eventHandler,
            messagingService = messagingService,
            metricsRecorder = metricsRecorder,
            properties = SlackProperties(enabled = true, maxConcurrentRequests = 5)
        )
        val controller = SlackEventController(objectMapper, eventProcessor)

        val commandSlot = slot<AgentCommand>()
        coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult(
            success = true,
            content = "RAG found the policy update. MCP verified ticket ARC-101 owner is @alice.",
            toolsUsed = listOf("mcp_jira_lookup")
        )
        coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(
            ok = true,
            ts = "1710000.0003",
            channel = "C456"
        )

        val payload = """
            {
              "type": "event_callback",
              "event_id": "Ev-rag-mcp-001",
              "event": {
                "type": "message",
                "user": "U123",
                "channel": "C456",
                "text": "who owns ARC-101 now?",
                "ts": "1710000.0002",
                "thread_ts": "1710000.0000"
              }
            }
        """.trimIndent()

        val response = controller.handleEvent(payload)
        response.statusCode shouldBe HttpStatus.OK

        coVerify(timeout = 2_000) {
            messagingService.sendMessage(
                "C456",
                "RAG found the policy update. MCP verified ticket ARC-101 owner is @alice.",
                "1710000.0000"
            )
        }
        commandSlot.captured.metadata["sessionId"] shouldBe "slack-C456-1710000.0000"
    }

    @Test
    fun `slash command flow should acknowledge immediately and post guard warning in thread`() = runTest {
        val commandHandler = DefaultSlackCommandHandler(agentExecutor, messagingService)
        val commandProcessor = SlackCommandProcessor(
            commandHandler = commandHandler,
            messagingService = messagingService,
            metricsRecorder = metricsRecorder,
            properties = SlackProperties(enabled = true, maxConcurrentRequests = 5)
        )
        val controller = SlackCommandController(commandProcessor)

        val commandSlot = slot<AgentCommand>()
        coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult(
            success = false,
            content = null,
            errorCode = AgentErrorCode.GUARD_REJECTED,
            errorMessage = "Blocked by input policy"
        )
        coEvery {
            messagingService.sendMessage("C456", any(), null)
        } returns SlackApiResult(ok = true, ts = "1710000.1234", channel = "C456")
        coEvery {
            messagingService.sendMessage("C456", any(), "1710000.1234")
        } returns SlackApiResult(ok = true, ts = "1710000.1235", channel = "C456")
        coEvery { messagingService.sendResponseUrl(any(), any(), any()) } returns true

        val ack = controller.handleSlashCommand(
            command = "/jarvis",
            text = "summarize this with sensitive input",
            userId = "U123",
            userName = "alice",
            channelId = "C456",
            channelName = "general",
            responseUrl = "https://hooks.slack.com/commands/test",
            triggerId = "trigger-1"
        )
        ack.statusCode shouldBe HttpStatus.OK
        ack.body?.responseType shouldBe "ephemeral"

        coVerify(timeout = 2_000) {
            messagingService.sendMessage(
                "C456",
                match { it.contains(":warning:") && it.contains("Blocked by input policy") },
                "1710000.1234"
            )
        }
        commandSlot.captured.metadata["entrypoint"] shouldBe "slash"
        commandSlot.captured.metadata["source"] shouldBe "slack"
        commandSlot.captured.metadata["channel"] shouldBe "slack"
    }
}
