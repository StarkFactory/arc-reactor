package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.PostMessageResult
import com.arc.reactor.slack.tools.config.SlackToolsProperties
import com.arc.reactor.slack.tools.config.WriteIdempotencyProperties
import com.arc.reactor.slack.tools.usecase.SendMessageUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class SendMessageToolTest {

    private val sendMessageUseCase = mockk<SendMessageUseCase>()
    private val tool = SendMessageTool(sendMessageUseCase)

    @Test
    fun `sends message successfully`() {
        every { sendMessageUseCase.execute("C123", "Hello", null) } returns
            PostMessageResult(ok = true, ts = "1234.5678", channel = "C123")

        val result = tool.send_message("C123", "Hello", null)
        result shouldContain "\"ok\":true"
        result shouldContain "1234.5678"
    }

    @Test
    fun `returns error for blank channelId`() {
        val result = tool.send_message("", "Hello", null)
        result shouldContain "error"
        result shouldContain "channelId must be a valid Slack channel ID"
    }

    @Test
    fun `returns error for blank text`() {
        val result = tool.send_message("C123", "", null)
        result shouldContain "error"
        result shouldContain "text is required"
    }

    @Test
    fun `sends message with threadTs`() {
        every { sendMessageUseCase.execute("C123", "Reply", "1234.5678") } returns
            PostMessageResult(ok = true, ts = "1234.9999", channel = "C123")

        val result = tool.send_message("C123", "Reply", "1234.5678")
        result shouldContain "\"ok\":true"
    }

    @Test
    fun `returns error for invalid channelId format`() {
        val result = tool.send_message("123", "Hello", null)
        result shouldContain "channelId must be a valid Slack channel ID"
        verify(exactly = 0) { sendMessageUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `returns error for invalid thread timestamp format`() {
        val result = tool.send_message("C123", "Hello", "not-ts")
        result shouldContain "threadTs must be a valid Slack timestamp"
        verify(exactly = 0) { sendMessageUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `normalizes channel id and thread timestamp`() {
        every { sendMessageUseCase.execute("C123", "Hello", "1234.5678") } returns
            PostMessageResult(ok = true, ts = "1234.5678", channel = "C123")

        val result = tool.send_message(" C123 ", " Hello ", " 1234.5678 ")

        result shouldContain "\"ok\":true"
        verify { sendMessageUseCase.execute("C123", "Hello", "1234.5678") }
    }

    @Test
    fun `deduplicates repeated send with same idempotency key`() {
        val idempotencyService = InMemoryWriteOperationIdempotencyService(
            SlackToolsProperties(
                botToken = "xoxb-test-token",
                writeIdempotency = WriteIdempotencyProperties(enabled = true, ttlSeconds = 30, maxEntries = 100)
            )
        )
        val idempotentTool = SendMessageTool(sendMessageUseCase, idempotencyService)
        every { sendMessageUseCase.execute("C123", "Hello", null) } returns
            PostMessageResult(ok = true, ts = "1234.5678", channel = "C123")

        val first = idempotentTool.send_message("C123", "Hello", null, "request-1")
        val second = idempotentTool.send_message("C123", "Hello", null, "request-1")

        first shouldContain "1234.5678"
        second shouldContain "1234.5678"
        verify(exactly = 1) { sendMessageUseCase.execute("C123", "Hello", null) }
    }

    @Test
    fun `handles API error`() {
        every { sendMessageUseCase.execute("C123", "Hello", null) } returns
            PostMessageResult(ok = false, error = "channel_not_found")

        val result = tool.send_message("C123", "Hello", null)
        result shouldContain "\"ok\":false"
        result shouldContain "channel_not_found"
    }
}
