package com.arc.reactor.slack

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.slack.handler.DefaultSlackEventHandler
import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultSlackEventHandlerTest {

    private val agentExecutor = mockk<AgentExecutor>()
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val handler = DefaultSlackEventHandler(agentExecutor, messagingService)

    private fun command(
        text: String = "<@BOT123> hello",
        userId: String = "U123",
        channelId: String = "C456",
        ts: String = "1234.5678",
        threadTs: String? = null
    ) = SlackEventCommand(
        eventType = "app_mention",
        userId = userId,
        channelId = channelId,
        text = text,
        ts = ts,
        threadTs = threadTs
    )

    @Nested
    inner class MentionTagStripping {

        @Test
        fun `strips bot mention tag from text`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Hi!")

            handler.handleAppMention(command(text = "<@BOT123> what is Kotlin?"))

            commandSlot.captured.userPrompt shouldBe "what is Kotlin?"
        }

        @Test
        fun `strips multiple mention tags`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Ok")

            handler.handleAppMention(command(text = "<@BOT123> tell <@U999> about Kotlin"))

            commandSlot.captured.userPrompt shouldBe "tell  about Kotlin"
        }

        @Test
        fun `skips empty text after stripping mention`() = runTest {
            handler.handleAppMention(command(text = "<@BOT123>"))

            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }

        @Test
        fun `skips whitespace-only text after stripping mention`() = runTest {
            handler.handleAppMention(command(text = "<@BOT123>   "))

            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }
    }

    @Nested
    inner class SessionIdMapping {

        @Test
        fun `creates session ID from channel and ts when no thread`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            handler.handleAppMention(command(channelId = "C789", ts = "1111.2222"))

            val sessionId = commandSlot.captured.metadata["sessionId"] as String
            sessionId shouldBe "slack-C789-1111.2222"
        }

        @Test
        fun `creates session ID from channel and threadTs when in thread`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            handler.handleAppMention(
                command(channelId = "C789", ts = "2222.3333", threadTs = "1111.0000")
            )

            val sessionId = commandSlot.captured.metadata["sessionId"] as String
            sessionId shouldBe "slack-C789-1111.0000"
        }

        @Test
        fun `includes source metadata`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            handler.handleAppMention(command())

            commandSlot.captured.metadata["source"] shouldBe "slack"
        }

        @Test
        fun `includes channel metadata`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            handler.handleAppMention(command())

            commandSlot.captured.metadata["channel"] shouldBe "slack"
        }

        @Test
        fun `tracks mention thread for follow up routing`() = runTest {
            val tracker = SlackThreadTracker()
            val trackingHandler = DefaultSlackEventHandler(
                agentExecutor = agentExecutor,
                messagingService = messagingService,
                threadTracker = tracker
            )
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "Done")

            trackingHandler.handleAppMention(command(channelId = "C789", ts = "1111.2222"))

            tracker.isTracked("C789", "1111.2222") shouldBe true
        }
    }

    @Nested
    inner class ResponseHandling {

        @Test
        fun `sends success content to Slack`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "The answer is 42")

            handler.handleAppMention(command())

            coVerify { messagingService.sendMessage("C456", "The answer is 42", "1234.5678") }
        }

        @Test
        fun `sends error message with warning emoji on failure`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(
                    success = false,
                    content = null,
                    errorCode = AgentErrorCode.RATE_LIMITED,
                    errorMessage = "Rate limit exceeded"
                )

            handler.handleAppMention(command())

            coVerify {
                messagingService.sendMessage("C456", match { it.shouldContain(":warning:"); true }, "1234.5678")
            }
        }

        @Test
        fun `formats guard rejection as warning response`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(
                    success = false,
                    content = null,
                    errorCode = AgentErrorCode.GUARD_REJECTED,
                    errorMessage = "Blocked by input policy"
                )

            handler.handleAppMention(command())

            coVerify {
                messagingService.sendMessage(
                    "C456",
                    match {
                        it.shouldStartWith(":warning:")
                        it.shouldContain("Blocked by input policy")
                        true
                    },
                    "1234.5678"
                )
            }
        }

        @Test
        fun `passes through rag and mcp enriched response content`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(
                    success = true,
                    content = "RAG says policy is 30 days. MCP tool confirmed latest ticket status."
                )

            handler.handleAppMention(command())

            coVerify {
                messagingService.sendMessage(
                    "C456",
                    "RAG says policy is 30 days. MCP tool confirmed latest ticket status.",
                    "1234.5678"
                )
            }
        }

        @Test
        fun `sends fallback message when content is null on success`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = null)

            handler.handleAppMention(command())

            coVerify {
                messagingService.sendMessage("C456", match { it.shouldContain("no response"); true }, "1234.5678")
            }
        }

        @Test
        fun `sends error message to Slack when executor throws`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } throws RuntimeException("LLM down")

            handler.handleAppMention(command())

            coVerify {
                messagingService.sendMessage("C456", match { it.shouldContain(":x:"); true }, "1234.5678")
            }
        }

        @Test
        fun `does not throw when Slack sendMessage returns non-ok result`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "The answer is 42")
            coEvery { messagingService.sendMessage(any(), any(), any()) } returns
                SlackApiResult(ok = false, error = "not_in_channel")

            handler.handleAppMention(command())

            coVerify(exactly = 1) {
                messagingService.sendMessage("C456", "The answer is 42", "1234.5678")
            }
        }
    }

    @Nested
    inner class MessageHandling {

        @Test
        fun `handleMessage delegates to agent with raw text`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Reply")

            handler.handleMessage(
                command(text = "follow-up question", threadTs = "1111.0000")
                    .copy(eventType = "message")
            )

            commandSlot.captured.userPrompt shouldBe "follow-up question"
            commandSlot.captured.userPrompt shouldNotContain "<@"
        }

        @Test
        fun `handleMessage skips blank text`() = runTest {
            handler.handleMessage(
                command(text = "   ", threadTs = "1111.0000").copy(eventType = "message")
            )

            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }
    }
}
