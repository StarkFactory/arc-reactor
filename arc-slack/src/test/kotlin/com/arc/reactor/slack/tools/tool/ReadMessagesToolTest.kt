package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.ConversationHistoryResult
import com.arc.reactor.slack.tools.client.SlackMessage
import com.arc.reactor.slack.tools.usecase.ReadMessagesUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ReadMessagesToolTest {

    private val readMessagesUseCase = mockk<ReadMessagesUseCase>()
    private val tool = ReadMessagesTool(readMessagesUseCase)

    @Test
    fun `reads messages successfully`() {
        every { readMessagesUseCase.execute("C123", 10, null) } returns
            ConversationHistoryResult(
                ok = true,
                messages = listOf(
                    SlackMessage("U001", "Hello world", "1234.5678", null),
                    SlackMessage("U002", "Hi there", "1234.5679", null)
                )
            )

        val result = tool.read_messages("C123", null, null)
        result shouldContain "\"ok\":true"
        result shouldContain "Hello world"
        result shouldContain "Hi there"
    }

    @Test
    fun `returns error for blank channelId`() {
        val result = tool.read_messages("", null, null)
        result shouldContain "error"
        result shouldContain "channelId must be a valid Slack channel ID"
    }

    @Test
    fun `uses custom limit`() {
        every { readMessagesUseCase.execute("C123", 5, null) } returns
            ConversationHistoryResult(ok = true, messages = emptyList())

        val result = tool.read_messages("C123", 5, null)
        result shouldContain "\"ok\":true"
    }

    @Test
    fun `returns error for non-positive limit`() {
        val result = tool.read_messages("C123", 0, null)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 200"
        verify(exactly = 0) { readMessagesUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `returns error for limit above max`() {
        val result = tool.read_messages("C123", 201, null)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 200"
        verify(exactly = 0) { readMessagesUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `passes cursor when provided`() {
        every { readMessagesUseCase.execute("C123", 10, "next-cursor") } returns
            ConversationHistoryResult(ok = true, messages = emptyList(), nextCursor = null)

        val result = tool.read_messages(" C123 ", null, " next-cursor ")
        result shouldContain "\"ok\":true"
        verify { readMessagesUseCase.execute("C123", 10, "next-cursor") }
    }

    @Test
    fun `returns error for invalid channel id format`() {
        val result = tool.read_messages("123", null, null)
        result shouldContain "channelId must be a valid Slack channel ID"
        verify(exactly = 0) { readMessagesUseCase.execute(any(), any(), any()) }
    }
}
