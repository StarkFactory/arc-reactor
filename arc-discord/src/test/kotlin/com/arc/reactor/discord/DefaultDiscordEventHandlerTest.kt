package com.arc.reactor.discord

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.discord.handler.DefaultDiscordEventHandler
import com.arc.reactor.discord.model.DiscordEventCommand
import com.arc.reactor.discord.service.DiscordMessagingService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultDiscordEventHandlerTest {

    private val agentExecutor = mockk<AgentExecutor>()
    private val messagingService = mockk<DiscordMessagingService>(relaxed = true)
    private val handler = DefaultDiscordEventHandler(agentExecutor, messagingService, "123456789")

    private fun command(
        content: String = "<@123456789> hello",
        userId: String = "U123",
        channelId: String = "C456",
        messageId: String = "M789",
        username: String = "testuser",
        guildId: String? = "G001"
    ) = DiscordEventCommand(
        channelId = channelId,
        userId = userId,
        username = username,
        content = content,
        messageId = messageId,
        guildId = guildId
    )

    @Nested
    inner class MentionTagStripping {

        @Test
        fun `strips bot mention tag from text`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Hi!")

            handler.handleMessage(command(content = "<@123456789> what is Kotlin?"))

            commandSlot.captured.userPrompt shouldBe "what is Kotlin?"
        }

        @Test
        fun `strips mention with exclamation mark format`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Ok")

            handler.handleMessage(command(content = "<@!123456789> tell me about Kotlin"))

            commandSlot.captured.userPrompt shouldBe "tell me about Kotlin"
        }

        @Test
        fun `strips multiple mention tags`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Ok")

            handler.handleMessage(
                command(content = "<@123456789> tell <@999999> about Kotlin")
            )

            commandSlot.captured.userPrompt shouldBe "tell  about Kotlin"
        }

        @Test
        fun `skips empty text after stripping mention`() = runTest {
            handler.handleMessage(command(content = "<@123456789>"))

            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }

        @Test
        fun `skips whitespace-only text after stripping mention`() = runTest {
            handler.handleMessage(command(content = "<@123456789>   "))

            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }
    }

    @Nested
    inner class SessionIdMapping {

        @Test
        fun `creates session ID from channel ID`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            handler.handleMessage(command(channelId = "C789"))

            val sessionId = commandSlot.captured.metadata["sessionId"] as String
            sessionId shouldBe "discord-C789"
        }

        @Test
        fun `includes source metadata`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            handler.handleMessage(command())

            commandSlot.captured.metadata["source"] shouldBe "discord"
        }

        @Test
        fun `passes userId to agent command`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")

            handler.handleMessage(command(userId = "U999"))

            commandSlot.captured.userId shouldBe "U999"
        }
    }

    @Nested
    inner class ResponseHandling {

        @Test
        fun `sends success content to Discord`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "The answer is 42")

            handler.handleMessage(command())

            coVerify {
                messagingService.sendMessage("C456", "The answer is 42")
            }
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

            handler.handleMessage(command())

            coVerify {
                messagingService.sendMessage(
                    "C456",
                    match {
                        it.shouldContain(":warning:")
                        it.shouldContain("Rate limit exceeded")
                        true
                    }
                )
            }
        }

        @Test
        fun `sends fallback message when content is null on success`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = null)

            handler.handleMessage(command())

            coVerify {
                messagingService.sendMessage(
                    "C456",
                    match { it.shouldContain("no response"); true }
                )
            }
        }

        @Test
        fun `sends error message to Discord when executor throws`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } throws
                RuntimeException("LLM down")

            handler.handleMessage(command())

            coVerify {
                messagingService.sendMessage(
                    "C456",
                    match { it.shouldContain(":x:"); true }
                )
            }
        }
    }
}
