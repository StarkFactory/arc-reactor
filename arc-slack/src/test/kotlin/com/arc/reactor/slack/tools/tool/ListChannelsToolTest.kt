package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.ConversationsListResult
import com.arc.reactor.slack.tools.client.SlackChannel
import com.arc.reactor.slack.tools.usecase.ListChannelsUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ListChannelsToolTest {

    private val listChannelsUseCase = mockk<ListChannelsUseCase>()
    private val tool = ListChannelsTool(listChannelsUseCase)

    @Test
    fun `lists channels successfully`() {
        every { listChannelsUseCase.execute(100, null) } returns
            ConversationsListResult(
                ok = true,
                channels = listOf(
                    SlackChannel("C123", "general", "General discussion", 50, false),
                    SlackChannel("C456", "random", "Random stuff", 30, false)
                )
            )

        val result = tool.list_channels(null, null)
        result shouldContain "\"ok\":true"
        result shouldContain "general"
        result shouldContain "random"
    }

    @Test
    fun `uses custom limit`() {
        every { listChannelsUseCase.execute(5, null) } returns
            ConversationsListResult(ok = true, channels = emptyList())

        val result = tool.list_channels(5, null)
        result shouldContain "\"ok\":true"
    }

    @Test
    fun `handles pagination cursor`() {
        every { listChannelsUseCase.execute(100, "next_cursor") } returns
            ConversationsListResult(ok = true, channels = emptyList(), nextCursor = null)

        val result = tool.list_channels(null, " next_cursor ")
        result shouldContain "\"ok\":true"
        verify { listChannelsUseCase.execute(100, "next_cursor") }
    }

    @Test
    fun `returns error for non-positive limit`() {
        val result = tool.list_channels(0, null)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 200"
        verify(exactly = 0) { listChannelsUseCase.execute(any(), any()) }
    }

    @Test
    fun `returns error for limit above max`() {
        val result = tool.list_channels(201, null)
        result shouldContain "error"
        result shouldContain "limit must be between 1 and 200"
        verify(exactly = 0) { listChannelsUseCase.execute(any(), any()) }
    }
}
