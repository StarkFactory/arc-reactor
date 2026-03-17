package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.FindChannelsResult
import com.arc.reactor.slack.tools.client.SlackChannel
import com.arc.reactor.slack.tools.usecase.FindChannelUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * [FindChannelTool]의 단위 테스트.
 *
 * 채널 검색 도구의 부분 이름 검색, 정확한 일치, limit 검증을 테스트한다.
 */
class FindChannelToolTest {

    private val findChannelUseCase = mockk<FindChannelUseCase>()
    private val tool = FindChannelTool(findChannelUseCase)

    @Test
    fun `channels by partial name를 찾는다`() {
        every { findChannelUseCase.execute("gen", false, 10) } returns
            FindChannelsResult(
                ok = true,
                query = "gen",
                exactMatch = false,
                channels = listOf(
                    SlackChannel("C123", "general", "General", 50, false),
                    SlackChannel("C789", "gen-ai", "AI", 20, false)
                )
            )

        val result = tool.find_channel("gen", null, null)
        result shouldContain "\"ok\":true"
        result shouldContain "general"
        result shouldContain "gen-ai"
    }

    @Test
    fun `requested일 때 exact match를 사용한다`() {
        every { findChannelUseCase.execute("general", true, 5) } returns
            FindChannelsResult(ok = true, query = "general", exactMatch = true, channels = emptyList())

        val result = tool.find_channel("general", true, 5)
        result shouldContain "\"ok\":true"
        verify { findChannelUseCase.execute("general", true, 5) }
    }

    @Test
    fun `query before delegating를 트리밍한다`() {
        every { findChannelUseCase.execute("general", false, 10) } returns
            FindChannelsResult(ok = true, query = "general", exactMatch = false, channels = emptyList())

        val result = tool.find_channel(" general ", null, null)
        result shouldContain "\"ok\":true"
        verify { findChannelUseCase.execute("general", false, 10) }
    }

    @Test
    fun `blank query에 대해 error를 반환한다`() {
        val result = tool.find_channel("", null, null)
        result shouldContain "error"
        result shouldContain "query is required"
        verify(exactly = 0) { findChannelUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `non-positive limit에 대해 error를 반환한다`() {
        val result = tool.find_channel("gen", null, 0)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 50"
        verify(exactly = 0) { findChannelUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `limit above max에 대해 error를 반환한다`() {
        val result = tool.find_channel("gen", null, 51)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 50"
        verify(exactly = 0) { findChannelUseCase.execute(any(), any(), any()) }
    }
}
