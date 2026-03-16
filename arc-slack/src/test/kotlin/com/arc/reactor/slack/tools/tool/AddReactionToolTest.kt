package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.SimpleResult
import com.arc.reactor.slack.tools.usecase.AddReactionUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class AddReactionToolTest {

    private val addReactionUseCase = mockk<AddReactionUseCase>()
    private val tool = AddReactionTool(addReactionUseCase)

    @Test
    fun `reaction successfully를 추가한다`() {
        every { addReactionUseCase.execute("C123", "1234.5678", "thumbsup") } returns
            SimpleResult(ok = true)

        val result = tool.add_reaction("C123", "1234.5678", "thumbsup")
        result shouldContain "\"ok\":true"
    }

    @Test
    fun `colons from emoji name를 제거한다`() {
        every { addReactionUseCase.execute("C123", "1234.5678", "heart") } returns
            SimpleResult(ok = true)

        tool.add_reaction("C123", "1234.5678", ":heart:")
        verify { addReactionUseCase.execute("C123", "1234.5678", "heart") }
    }

    @Test
    fun `blank channelId에 대해 error를 반환한다`() {
        val result = tool.add_reaction("", "1234.5678", "thumbsup")
        result shouldContain "error"
        result shouldContain "channelId must be a valid Slack channel ID"
    }

    @Test
    fun `blank timestamp에 대해 error를 반환한다`() {
        val result = tool.add_reaction("C123", "", "thumbsup")
        result shouldContain "error"
        result shouldContain "timestamp must be a valid Slack timestamp"
    }

    @Test
    fun `blank emoji에 대해 error를 반환한다`() {
        val result = tool.add_reaction("C123", "1234.5678", "")
        result shouldContain "error"
        result shouldContain "emoji must contain letters, numbers, underscore, plus or dash"
    }

    @Test
    fun `API error를 처리한다`() {
        every { addReactionUseCase.execute("C123", "1234.5678", "invalid") } returns
            SimpleResult(ok = false, error = "invalid_name")

        val result = tool.add_reaction("C123", "1234.5678", "invalid")
        result shouldContain "\"ok\":false"
        result shouldContain "invalid_name"
    }

    @Test
    fun `invalid timestamp format에 대해 error를 반환한다`() {
        val result = tool.add_reaction("C123", "not-ts", "thumbsup")
        result shouldContain "timestamp must be a valid Slack timestamp"
        verify(exactly = 0) { addReactionUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `invalid emoji format에 대해 error를 반환한다`() {
        val result = tool.add_reaction("C123", "1234.5678", "thumbs up")
        result shouldContain "emoji must contain letters, numbers, underscore, plus or dash"
        verify(exactly = 0) { addReactionUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `channel id and timestamp를 정규화한다`() {
        every { addReactionUseCase.execute("C123", "1234.5678", "heart") } returns
            SimpleResult(ok = true)

        val result = tool.add_reaction(" C123 ", " 1234.5678 ", ":heart:")
        result shouldContain "\"ok\":true"
        verify { addReactionUseCase.execute("C123", "1234.5678", "heart") }
    }
}
