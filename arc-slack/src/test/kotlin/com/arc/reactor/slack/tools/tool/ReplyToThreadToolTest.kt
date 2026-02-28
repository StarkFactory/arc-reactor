package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.PostMessageResult
import com.arc.reactor.slack.tools.usecase.ReplyToThreadUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ReplyToThreadToolTest {

    private val replyToThreadUseCase = mockk<ReplyToThreadUseCase>()
    private val tool = ReplyToThreadTool(replyToThreadUseCase)

    @Test
    fun `replies to thread successfully`() {
        every { replyToThreadUseCase.execute("C123", "1234.5678", "Reply text") } returns
            PostMessageResult(ok = true, ts = "1234.9999", channel = "C123")

        val result = tool.reply_to_thread("C123", "1234.5678", "Reply text")
        result shouldContain "\"ok\":true"
        result shouldContain "1234.9999"
    }

    @Test
    fun `returns error for blank channelId`() {
        val result = tool.reply_to_thread("", "1234.5678", "Reply")
        result shouldContain "error"
        result shouldContain "channelId must be a valid Slack channel ID"
    }

    @Test
    fun `returns error for blank threadTs`() {
        val result = tool.reply_to_thread("C123", "", "Reply")
        result shouldContain "error"
        result shouldContain "threadTs must be a valid Slack timestamp"
    }

    @Test
    fun `returns error for blank text`() {
        val result = tool.reply_to_thread("C123", "1234.5678", "")
        result shouldContain "error"
        result shouldContain "text is required"
    }

    @Test
    fun `handles API error`() {
        every { replyToThreadUseCase.execute("C123", "1234.5678", "Reply") } returns
            PostMessageResult(ok = false, error = "thread_not_found")

        val result = tool.reply_to_thread("C123", "1234.5678", "Reply")
        result shouldContain "\"ok\":false"
        result shouldContain "thread_not_found"
    }

    @Test
    fun `returns error for invalid channelId format`() {
        val result = tool.reply_to_thread("123", "1234.5678", "Reply")
        result shouldContain "channelId must be a valid Slack channel ID"
        verify(exactly = 0) { replyToThreadUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `returns error for invalid thread timestamp format`() {
        val result = tool.reply_to_thread("C123", "not-ts", "Reply")
        result shouldContain "threadTs must be a valid Slack timestamp"
        verify(exactly = 0) { replyToThreadUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `normalizes inputs before delegating`() {
        every { replyToThreadUseCase.execute("C123", "1234.5678", "Reply") } returns
            PostMessageResult(ok = true, ts = "1234.9999", channel = "C123")

        val result = tool.reply_to_thread(" C123 ", " 1234.5678 ", " Reply ")

        result shouldContain "\"ok\":true"
        verify { replyToThreadUseCase.execute("C123", "1234.5678", "Reply") }
    }
}
