package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.ConversationHistoryResult
import com.arc.reactor.slack.tools.client.SlackMessage
import com.arc.reactor.slack.tools.usecase.ReadThreadRepliesUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ReadThreadRepliesToolTest {

    private val readThreadRepliesUseCase = mockk<ReadThreadRepliesUseCase>()
    private val tool = ReadThreadRepliesTool(readThreadRepliesUseCase)

    @Test
    fun `reads thread replies successfully`() {
        every { readThreadRepliesUseCase.execute("C123", "1234.5678", 10, null) } returns
            ConversationHistoryResult(
                ok = true,
                messages = listOf(
                    SlackMessage("U001", "first reply", "1234.5679", "1234.5678"),
                    SlackMessage("U002", "second reply", "1234.5680", "1234.5678")
                )
            )

        val result = tool.read_thread_replies("C123", "1234.5678", null, null)
        result shouldContain "\"ok\":true"
        result shouldContain "first reply"
        result shouldContain "second reply"
    }

    @Test
    fun `returns error for blank channelId`() {
        val result = tool.read_thread_replies("", "1234.5678", null, null)
        result shouldContain "error"
        result shouldContain "channelId must be a valid Slack channel ID"
    }

    @Test
    fun `returns error for blank threadTs`() {
        val result = tool.read_thread_replies("C123", "", null, null)
        result shouldContain "error"
        result shouldContain "threadTs must be a valid Slack timestamp"
    }

    @Test
    fun `returns error for non-positive limit`() {
        val result = tool.read_thread_replies("C123", "1234.5678", 0, null)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 200"
        verify(exactly = 0) { readThreadRepliesUseCase.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `returns error for limit above max`() {
        val result = tool.read_thread_replies("C123", "1234.5678", 201, null)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 200"
        verify(exactly = 0) { readThreadRepliesUseCase.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `uses custom limit`() {
        every { readThreadRepliesUseCase.execute("C123", "1234.5678", 5, null) } returns
            ConversationHistoryResult(ok = true, messages = emptyList())

        val result = tool.read_thread_replies("C123", "1234.5678", 5, null)
        result shouldContain "\"ok\":true"
    }

    @Test
    fun `passes cursor when provided`() {
        every { readThreadRepliesUseCase.execute("C123", "1234.5678", 10, "next-cursor") } returns
            ConversationHistoryResult(ok = true, messages = emptyList(), nextCursor = null)

        val result = tool.read_thread_replies(" C123 ", " 1234.5678 ", null, " next-cursor ")
        result shouldContain "\"ok\":true"
        verify { readThreadRepliesUseCase.execute("C123", "1234.5678", 10, "next-cursor") }
    }

    @Test
    fun `returns error for invalid channel id format`() {
        val result = tool.read_thread_replies("123", "1234.5678", null, null)
        result shouldContain "channelId must be a valid Slack channel ID"
        verify(exactly = 0) { readThreadRepliesUseCase.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `returns error for invalid thread timestamp format`() {
        val result = tool.read_thread_replies("C123", "abc", null, null)
        result shouldContain "threadTs must be a valid Slack timestamp"
        verify(exactly = 0) { readThreadRepliesUseCase.execute(any(), any(), any(), any()) }
    }
}
