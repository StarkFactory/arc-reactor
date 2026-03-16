package com.arc.reactor.slack

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.slack.handler.DefaultSlackCommandHandler
import com.arc.reactor.slack.handler.SlackReminderStore
import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.slack.service.SlackUserEmailResolver
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
        fun `빈 text sends guide message via response_url`() = runTest {
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
        fun `channel post succeeds일 때 posts to channel and replies in thread`() = runTest {
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
            commandSlot.captured.metadata["intent"] shouldBe "general"

            coVerify(exactly = 1) { messagingService.sendMessage("C123", any(), null) }
            coVerify(exactly = 1) { messagingService.sendMessage("C123", "You have 3 tasks today.", "1111.2222") }
            coVerify(exactly = 0) { messagingService.sendResponseUrl(any(), any(), any()) }
        }

        @Test
        fun `requester email metadata when resolver succeeds를 포함한다`() = runTest {
            val resolver = mockk<SlackUserEmailResolver>()
            val emailHandler = DefaultSlackCommandHandler(
                agentExecutor = agentExecutor,
                messagingService = messagingService,
                userEmailResolver = resolver
            )
            coEvery { resolver.resolveEmail("U456") } returns "alice@example.com"
            coEvery {
                messagingService.sendMessage("C123", any(), null)
            } returns SlackApiResult(ok = true, ts = "1111.2222", channel = "C123")
            coEvery {
                messagingService.sendMessage("C123", any(), "1111.2222")
            } returns SlackApiResult(ok = true, ts = "1111.3333", channel = "C123")
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            emailHandler.handleSlashCommand(slashCommand())

            commandSlot.captured.metadata["requesterEmail"] shouldBe "alice@example.com"
            commandSlot.captured.metadata["slackUserEmail"] shouldBe "alice@example.com"
            commandSlot.captured.metadata["userEmail"] shouldBe "alice@example.com"
        }

        @Test
        fun `brief은(는) intent rewrites prompt and tags intent metadata`() = runTest {
            coEvery {
                messagingService.sendMessage("C123", any(), null)
            } returns SlackApiResult(ok = true, ts = "1111.2222", channel = "C123")
            coEvery {
                messagingService.sendMessage("C123", any(), "1111.2222")
            } returns SlackApiResult(ok = true, ts = "1111.3333", channel = "C123")

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "brief done")

            handler.handleSlashCommand(slashCommand(text = "brief release handoff"))

            commandSlot.captured.userPrompt shouldContain "Create a personal daily brief for the user."
            commandSlot.captured.userPrompt shouldContain "Focus: release handoff"
            commandSlot.captured.metadata["intent"] shouldBe "brief"
        }

        @Test
        fun `my-work intent은(는) prompt and tags intent metadata를 재작성한다`() = runTest {
            coEvery {
                messagingService.sendMessage("C123", any(), null)
            } returns SlackApiResult(ok = false, error = "not_in_channel")
            coEvery { messagingService.sendResponseUrl(any(), any(), any()) } returns true

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "my work")

            handler.handleSlashCommand(slashCommand(text = "my-work sprint board"))

            commandSlot.captured.userPrompt shouldContain "Summarize my work status as my personal assistant."
            commandSlot.captured.userPrompt shouldContain "Scope: sprint board"
            commandSlot.captured.metadata["intent"] shouldBe "my_work"
            coVerify(exactly = 1) { messagingService.sendResponseUrl(any(), any(), any()) }
        }

        @Test
        fun `response_url when channel post fails로 폴백한다`() = runTest {
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
        fun `response_url when thread reply send fails로 폴백한다`() = runTest {
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
        fun `thread when channel post succeeds를 추적한다`() = runTest {
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
    inner class ReminderCommands {

        @Test
        fun `add stores reminder and replies without agent execution를 리마인드한다`() = runTest {
            val store = SlackReminderStore()
            val reminderHandler = DefaultSlackCommandHandler(
                agentExecutor = agentExecutor,
                messagingService = messagingService,
                reminderStore = store
            )
            val responses = mutableListOf<String>()
            coEvery { messagingService.sendResponseUrl(any(), capture(responses), any()) } returns true

            reminderHandler.handleSlashCommand(slashCommand(text = "remind Submit PTO by Friday"))

            responses.last() shouldContain "Saved reminder #1: Submit PTO by Friday"
            store.list("U456").map { it.text } shouldBe listOf("Submit PTO by Friday")
            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
            coVerify(exactly = 0) { messagingService.sendMessage(any(), any(), any()) }
        }

        @Test
        fun `list returns reminders for the user를 리마인드한다`() = runTest {
            val store = SlackReminderStore()
            store.add("U456", "A")
            store.add("U456", "B")
            val reminderHandler = DefaultSlackCommandHandler(
                agentExecutor = agentExecutor,
                messagingService = messagingService,
                reminderStore = store
            )
            val responses = mutableListOf<String>()
            coEvery { messagingService.sendResponseUrl(any(), capture(responses), any()) } returns true

            reminderHandler.handleSlashCommand(slashCommand(text = "remind list"))

            responses.last() shouldContain "Your reminders:"
            responses.last() shouldContain "- #1 A"
            responses.last() shouldContain "- #2 B"
            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }

        @Test
        fun `done marks reminder complete를 리마인드한다`() = runTest {
            val store = SlackReminderStore()
            store.add("U456", "A")
            val reminderHandler = DefaultSlackCommandHandler(
                agentExecutor = agentExecutor,
                messagingService = messagingService,
                reminderStore = store
            )
            val responses = mutableListOf<String>()
            coEvery { messagingService.sendResponseUrl(any(), capture(responses), any()) } returns true

            reminderHandler.handleSlashCommand(slashCommand(text = "remind done 1"))

            responses.last() shouldContain "Completed reminder #1: A"
            store.list("U456") shouldBe emptyList()
            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }

        @Test
        fun `remind commands return unavailable when store은(는) not configured이다`() = runTest {
            coEvery { messagingService.sendResponseUrl(any(), any(), any()) } returns true

            handler.handleSlashCommand(slashCommand(text = "remind list"))

            coVerify(exactly = 1) {
                messagingService.sendResponseUrl(
                    "https://hooks.slack.com/commands/test",
                    match { it.contains("temporarily unavailable") },
                    "ephemeral"
                )
            }
            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }
    }

    @Nested
    inner class AgentScenarios {

        @Test
        fun `guard rejected result은(는) surfaced as warning이다`() = runTest {
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
        fun `generic refusal response은(는) rewritten into best effort answer이다`() = runTest {
            coEvery {
                messagingService.sendMessage("C123", any(), null)
            } returns SlackApiResult(ok = true, ts = "1111.2222", channel = "C123")
            coEvery {
                messagingService.sendMessage("C123", any(), "1111.2222")
            } returns SlackApiResult(ok = true, ts = "1111.3333", channel = "C123")
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "요청하신 작업을 수행할 수 없습니다")

            handler.handleSlashCommand(slashCommand(text = "오늘 할 일 우선순위 알려줘"))

            coVerify {
                messagingService.sendMessage(
                    "C123",
                    match {
                        it.shouldContain("바로 실행 가능한 초안으로 정리합니다.")
                        it.shouldNotContain("요청하신 작업을 수행할 수 없습니다")
                        true
                    },
                    "1111.2222"
                )
            }
        }

        @Test
        fun `react and mcp success response은(는) delivered in thread이다`() = runTest {
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
