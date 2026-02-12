package com.arc.reactor.line

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.line.handler.DefaultLineEventHandler
import com.arc.reactor.line.model.LineEventCommand
import com.arc.reactor.line.service.LineMessagingService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultLineEventHandlerTest {

    private val agentExecutor = mockk<AgentExecutor>()
    private val messagingService = mockk<LineMessagingService>(relaxed = true)
    private val handler = DefaultLineEventHandler(agentExecutor, messagingService)

    private fun command(
        userId: String = "U123",
        groupId: String? = null,
        roomId: String? = null,
        text: String = "hello",
        replyToken: String = "reply-token-abc",
        sourceType: String = "user",
        messageId: String = "msg-001"
    ) = LineEventCommand(
        userId = userId,
        groupId = groupId,
        roomId = roomId,
        text = text,
        replyToken = replyToken,
        sourceType = sourceType,
        messageId = messageId
    )

    @Nested
    inner class SessionIdMapping {

        @Test
        fun `creates session ID from userId for user source`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")
            coEvery { messagingService.replyMessage(any(), any()) } returns true

            handler.handleMessage(command(userId = "U999"))

            val sessionId = commandSlot.captured.metadata["sessionId"] as String
            sessionId shouldBe "line-U999"
        }

        @Test
        fun `creates session ID from groupId for group source`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")
            coEvery { messagingService.replyMessage(any(), any()) } returns true

            handler.handleMessage(
                command(userId = "U123", groupId = "G456", sourceType = "group")
            )

            val sessionId = commandSlot.captured.metadata["sessionId"] as String
            sessionId shouldBe "line-G456"
        }

        @Test
        fun `creates session ID from roomId for room source`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")
            coEvery { messagingService.replyMessage(any(), any()) } returns true

            handler.handleMessage(
                command(userId = "U123", roomId = "R789", sourceType = "room")
            )

            val sessionId = commandSlot.captured.metadata["sessionId"] as String
            sessionId shouldBe "line-R789"
        }

        @Test
        fun `includes source metadata as line`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult(success = true, content = "Done")
            coEvery { messagingService.replyMessage(any(), any()) } returns true

            handler.handleMessage(command())

            commandSlot.captured.metadata["source"] shouldBe "line"
        }
    }

    @Nested
    inner class SuccessResponse {

        @Test
        fun `calls replyMessage on success`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "The answer is 42")
            coEvery { messagingService.replyMessage(any(), any()) } returns true

            handler.handleMessage(command(replyToken = "tok-1"))

            coVerify {
                messagingService.replyMessage("tok-1", "The answer is 42")
            }
        }

        @Test
        fun `sends fallback message when content is null`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = null)
            coEvery { messagingService.replyMessage(any(), any()) } returns true

            handler.handleMessage(command())

            coVerify {
                messagingService.replyMessage(
                    any(),
                    match { it.shouldContain("no response"); true }
                )
            }
        }
    }

    @Nested
    inner class ReplyFallback {

        @Test
        fun `falls back to pushMessage when replyMessage fails`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "response text")
            coEvery { messagingService.replyMessage(any(), any()) } returns false

            handler.handleMessage(command(userId = "U123"))

            coVerify { messagingService.pushMessage("U123", "response text") }
        }

        @Test
        fun `falls back to pushMessage with groupId for group source`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(success = true, content = "group reply")
            coEvery { messagingService.replyMessage(any(), any()) } returns false

            handler.handleMessage(
                command(
                    userId = "U123",
                    groupId = "G456",
                    sourceType = "group"
                )
            )

            coVerify { messagingService.pushMessage("G456", "group reply") }
        }
    }

    @Nested
    inner class ErrorResponse {

        @Test
        fun `sends warning message on agent failure`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult(
                    success = false,
                    content = null,
                    errorCode = AgentErrorCode.RATE_LIMITED,
                    errorMessage = "Rate limit exceeded"
                )
            coEvery { messagingService.replyMessage(any(), any()) } returns true

            handler.handleMessage(command())

            coVerify {
                messagingService.replyMessage(
                    any(),
                    match { it.contains("Rate limit exceeded"); true }
                )
            }
        }

        @Test
        fun `sends error via push when executor throws`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } throws
                RuntimeException("LLM down")

            handler.handleMessage(command(userId = "U123"))

            coVerify {
                messagingService.pushMessage(
                    "U123",
                    match { it.contains("internal error"); true }
                )
            }
        }
    }

    @Nested
    inner class EmptyTextHandling {

        @Test
        fun `skips empty text`() = runTest {
            handler.handleMessage(command(text = ""))

            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }

        @Test
        fun `skips whitespace-only text`() = runTest {
            handler.handleMessage(command(text = "   "))

            coVerify(exactly = 0) { agentExecutor.execute(any<AgentCommand>()) }
        }
    }
}
