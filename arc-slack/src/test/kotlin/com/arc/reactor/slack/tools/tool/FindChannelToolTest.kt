package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.FindChannelsResult
import com.arc.reactor.slack.tools.client.SlackChannel
import com.arc.reactor.slack.tools.usecase.FindChannelUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class FindChannelToolTest {

    private val findChannelUseCase = mockk<FindChannelUseCase>()
    private val tool = FindChannelTool(findChannelUseCase)

    @Test
    fun `finds channels by partial name`() {
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
    fun `uses exact match when requested`() {
        every { findChannelUseCase.execute("general", true, 5) } returns
            FindChannelsResult(ok = true, query = "general", exactMatch = true, channels = emptyList())

        val result = tool.find_channel("general", true, 5)
        result shouldContain "\"ok\":true"
        verify { findChannelUseCase.execute("general", true, 5) }
    }

    @Test
    fun `trims query before delegating`() {
        every { findChannelUseCase.execute("general", false, 10) } returns
            FindChannelsResult(ok = true, query = "general", exactMatch = false, channels = emptyList())

        val result = tool.find_channel(" general ", null, null)
        result shouldContain "\"ok\":true"
        verify { findChannelUseCase.execute("general", false, 10) }
    }

    @Test
    fun `returns error for blank query`() {
        val result = tool.find_channel("", null, null)
        result shouldContain "error"
        result shouldContain "query is required"
        verify(exactly = 0) { findChannelUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `returns error for non-positive limit`() {
        val result = tool.find_channel("gen", null, 0)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 50"
        verify(exactly = 0) { findChannelUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `returns error for limit above max`() {
        val result = tool.find_channel("gen", null, 51)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 50"
        verify(exactly = 0) { findChannelUseCase.execute(any(), any(), any()) }
    }
}
