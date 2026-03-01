package com.arc.reactor.slack

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.slack.handler.DefaultSlackCommandHandler
import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultSlackCommandHandlerTest {

    private val agentExecutor = mockk<AgentExecutor>()
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val handler = DefaultSlackCommandHandler(agentExecutor, messagingService)

    private fun slashCommand(
        text: String = "What should I do today?",
        channelId: String = "C123",
        userId: String = "U456",
        responseUrl: String = "https://hooks.slack.com/commands/test"
    ) = SlackSlashCommand(
        command = "/jarvis",
        text = text,
        userId = userId,
        userName = "alice",
        channelId = channelId,
        channelName = "general",
        responseUrl = responseUrl,
        triggerId = "trigger-1"
    )

    @Nested
    inner class Validation {

        @Test
        fun `blank text sends guide message via response_url`() = runTest {
            coEvery { messagingService.sendResponseUrl(any(), any(), any()) } returns true

            handler.handleSlashCommand(slashCommand(text = "   "))

            coVerify(exactly = 1) {
                messagingService.sendResponseUrl(
                    "https://hooks.slack.com/commands/test",
                    match { it.contains("Please enter a question") },
                    "ephemeral"
                )
            }
            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }
    }

    @Nested
    inner class Routing {

        @Test
        fun `posts to channel and replies in thread when channel post succeeds`() = runTest {
            coEvery {
                messagingService.sendMessage("C123", any(), null)
            } returns SlackApiResult(ok = true, ts = "1111.2222", channel = "C123")
            coEvery {
                messagingService.sendMessage("C123", any(), "1111.2222")
            } returns SlackApiResult(ok = true, ts = "1111.3333", channel = "C123")

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "You have 3 tasks today.")

            handler.handleSlashCommand(slashCommand())

            commandSlot.captured.userPrompt shouldBe "What should I do today?"
            commandSlot.captured.metadata["entrypoint"] shouldBe "slash"
            commandSlot.captured.metadata["sessionId"] shouldBe "slack-C123-1111.2222"
            commandSlot.captured.metadata["source"] shouldBe "slack"
            commandSlot.captured.metadata["channel"] shouldBe "slack"
            commandSlot.captured.metadata["channelId"] shouldBe "C123"

            coVerify(exactly = 1) { messagingService.sendMessage("C123", any(), null) }
            coVerify(exactly = 1) { messagingService.sendMessage("C123", "You have 3 tasks today.", "1111.2222") }
            coVerify(exactly = 0) { messagingService.sendResponseUrl(any(), any(), any()) }
        }

        @Test
        fun `falls back to response_url when channel post fails`() = runTest {
            coEvery {
                messagingService.sendMessage("C123", any(), null)
            } returns SlackApiResult(ok = false, error = "not_in_channel")

            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "Fallback response")
            coEvery { messagingService.sendResponseUrl(any(), any(), any()) } returns true

            handler.handleSlashCommand(slashCommand())

            coVerify(exactly = 1) { messagingService.sendResponseUrl("https://hooks.slack.com/commands/test", "Fallback response", "ephemeral") }
            coVerify(exactly = 0) { messagingService.sendMessage("C123", "Fallback response", any()) }
        }

        @Test
        fun `falls back to response_url when thread reply send fails`() = runTest {
            coEvery {
                messagingService.sendMessage("C123", any(), null)
            } returns SlackApiResult(ok = true, ts = "1111.2222", channel = "C123")
            coEvery {
                messagingService.sendMessage("C123", any(), "1111.2222")
            } returns SlackApiResult(ok = false, error = "channel_not_found")

            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "Thread reply fallback")
            coEvery { messagingService.sendResponseUrl(any(), any(), any()) } returns true

            handler.handleSlashCommand(slashCommand())

            coVerify(exactly = 1) {
                messagingService.sendResponseUrl(
                    "https://hooks.slack.com/commands/test",
                    "Thread reply fallback",
                    "ephemeral"
                )
            }
        }

        @Test
        fun `tracks thread when channel post succeeds`() = runTest {
            val tracker = SlackThreadTracker()
            val trackingHandler = DefaultSlackCommandHandler(agentExecutor, messagingService, threadTracker = tracker)
            coEvery {
                messagingService.sendMessage("C123", any(), null)
            } returns SlackApiResult(ok = true, ts = "1111.2222", channel = "C123")
            coEvery {
                messagingService.sendMessage("C123", any(), "1111.2222")
            } returns SlackApiResult(ok = true, ts = "1111.3333", channel = "C123")
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "Tracked")

            trackingHandler.handleSlashCommand(slashCommand())

            tracker.isTracked("C123", "1111.2222") shouldBe true
        }
    }

    @Nested
    inner class AgentScenarios {

        @Test
        fun `guard rejected result is surfaced as warning`() = runTest {
            coEvery {
                messagingService.sendMessage("C123", any(), null)
            } returns SlackApiResult(ok = true, ts = "1111.2222", channel = "C123")
            coEvery {
                messagingService.sendMessage("C123", any(), "1111.2222")
            } returns SlackApiResult(ok = true, ts = "1111.3333", channel = "C123")
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(
                    success = false,
                    content = null,
                    errorCode = AgentErrorCode.GUARD_REJECTED,
                    errorMessage = "Guard blocked unsafe prompt"
                )

            handler.handleSlashCommand(slashCommand())

            coVerify {
                messagingService.sendMessage(
                    "C123",
                    match {
                        it.shouldStartWith(":warning:")
                        it.shouldContain("Guard blocked unsafe prompt")
                        true
                    },
                    "1111.2222"
                )
            }
        }

        @Test
        fun `react and mcp success response is delivered in thread`() = runTest {
            coEvery {
                messagingService.sendMessage("C123", any(), null)
            } returns SlackApiResult(ok = true, ts = "1111.2222", channel = "C123")
            coEvery {
                messagingService.sendMessage("C123", any(), "1111.2222")
            } returns SlackApiResult(ok = true, ts = "1111.3333", channel = "C123")
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(
                    success = true,
                    content = "Resolved via ReAct + MCP: ticket owner is @alice.",
                    toolsUsed = listOf("mcp_jira_lookup")
                )

            handler.handleSlashCommand(slashCommand())

            coVerify {
                messagingService.sendMessage(
                    "C123",
                    "Resolved via ReAct + MCP: ticket owner is @alice.",
                    "1111.2222"
                )
            }
        }
    }
}
