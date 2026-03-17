package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.ConversationHistoryResult
import com.arc.reactor.slack.tools.client.SlackMessage
import com.arc.reactor.slack.tools.usecase.ReadThreadRepliesUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * [ReadThreadRepliesTool]의 단위 테스트.
 *
 * 스레드 답글 읽기 도구의 답글 조회, 입력 검증, 커서 전달을 테스트한다.
 */
class ReadThreadRepliesToolTest {

    private val readThreadRepliesUseCase = mockk<ReadThreadRepliesUseCase>()
    private val tool = ReadThreadRepliesTool(readThreadRepliesUseCase)

    @Test
    fun `thread replies successfully를 읽는다`() {
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
    fun `blank channelId에 대해 error를 반환한다`() {
        val result = tool.read_thread_replies("", "1234.5678", null, null)
        result shouldContain "error"
        result shouldContain "channelId must be a valid Slack channel ID"
    }

    @Test
    fun `blank threadTs에 대해 error를 반환한다`() {
        val result = tool.read_thread_replies("C123", "", null, null)
        result shouldContain "error"
        result shouldContain "threadTs must be a valid Slack timestamp"
    }

    @Test
    fun `non-positive limit에 대해 error를 반환한다`() {
        val result = tool.read_thread_replies("C123", "1234.5678", 0, null)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 200"
        verify(exactly = 0) { readThreadRepliesUseCase.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `limit above max에 대해 error를 반환한다`() {
        val result = tool.read_thread_replies("C123", "1234.5678", 201, null)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 200"
        verify(exactly = 0) { readThreadRepliesUseCase.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `custom limit를 사용한다`() {
        every { readThreadRepliesUseCase.execute("C123", "1234.5678", 5, null) } returns
            ConversationHistoryResult(ok = true, messages = emptyList())

        val result = tool.read_thread_replies("C123", "1234.5678", 5, null)
        result shouldContain "\"ok\":true"
    }

    @Test
    fun `cursor when provided를 전달한다`() {
        every { readThreadRepliesUseCase.execute("C123", "1234.5678", 10, "next-cursor") } returns
            ConversationHistoryResult(ok = true, messages = emptyList(), nextCursor = null)

        val result = tool.read_thread_replies(" C123 ", " 1234.5678 ", null, " next-cursor ")
        result shouldContain "\"ok\":true"
        verify { readThreadRepliesUseCase.execute("C123", "1234.5678", 10, "next-cursor") }
    }

    @Test
    fun `invalid channel id format에 대해 error를 반환한다`() {
        val result = tool.read_thread_replies("123", "1234.5678", null, null)
        result shouldContain "channelId must be a valid Slack channel ID"
        verify(exactly = 0) { readThreadRepliesUseCase.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `invalid thread timestamp format에 대해 error를 반환한다`() {
        val result = tool.read_thread_replies("C123", "abc", null, null)
        result shouldContain "threadTs must be a valid Slack timestamp"
        verify(exactly = 0) { readThreadRepliesUseCase.execute(any(), any(), any(), any()) }
    }
}
