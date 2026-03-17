package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.ConversationHistoryResult
import com.arc.reactor.slack.tools.client.SlackMessage
import com.arc.reactor.slack.tools.usecase.ReadMessagesUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * [ReadMessagesTool]의 단위 테스트.
 *
 * 메시지 읽기 도구의 채널 히스토리 조회, limit 검증, 커서 전달을 테스트한다.
 */
class ReadMessagesToolTest {

    private val readMessagesUseCase = mockk<ReadMessagesUseCase>()
    private val tool = ReadMessagesTool(readMessagesUseCase)

    @Test
    fun `messages successfully를 읽는다`() {
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
    fun `blank channelId에 대해 error를 반환한다`() {
        val result = tool.read_messages("", null, null)
        result shouldContain "error"
        result shouldContain "channelId must be a valid Slack channel ID"
    }

    @Test
    fun `custom limit를 사용한다`() {
        every { readMessagesUseCase.execute("C123", 5, null) } returns
            ConversationHistoryResult(ok = true, messages = emptyList())

        val result = tool.read_messages("C123", 5, null)
        result shouldContain "\"ok\":true"
    }

    @Test
    fun `non-positive limit에 대해 error를 반환한다`() {
        val result = tool.read_messages("C123", 0, null)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 200"
        verify(exactly = 0) { readMessagesUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `limit above max에 대해 error를 반환한다`() {
        val result = tool.read_messages("C123", 201, null)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 200"
        verify(exactly = 0) { readMessagesUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `cursor when provided를 전달한다`() {
        every { readMessagesUseCase.execute("C123", 10, "next-cursor") } returns
            ConversationHistoryResult(ok = true, messages = emptyList(), nextCursor = null)

        val result = tool.read_messages(" C123 ", null, " next-cursor ")
        result shouldContain "\"ok\":true"
        verify { readMessagesUseCase.execute("C123", 10, "next-cursor") }
    }

    @Test
    fun `invalid channel id format에 대해 error를 반환한다`() {
        val result = tool.read_messages("123", null, null)
        result shouldContain "channelId must be a valid Slack channel ID"
        verify(exactly = 0) { readMessagesUseCase.execute(any(), any(), any()) }
    }
}
