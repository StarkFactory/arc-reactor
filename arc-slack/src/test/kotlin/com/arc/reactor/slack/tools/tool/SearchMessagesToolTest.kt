package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.SearchMessagesResult
import com.arc.reactor.slack.tools.client.SlackSearchMessage
import com.arc.reactor.slack.tools.usecase.SearchMessagesUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class SearchMessagesToolTest {

    private val searchMessagesUseCase = mockk<SearchMessagesUseCase>()
    private val tool = SearchMessagesTool(searchMessagesUseCase)

    @Test
    fun `searches messages successfully`() {
        every { searchMessagesUseCase.execute("deploy", 20, 1) } returns
            SearchMessagesResult(
                ok = true,
                query = "deploy",
                matches = listOf(SlackSearchMessage(channelName = "general", text = "deploy done"))
            )

        val result = tool.search_messages("deploy", null, null)
        result shouldContain "\"ok\":true"
        result shouldContain "deploy done"
    }

    @Test
    fun `returns error for blank query`() {
        val result = tool.search_messages(" ", null, null)
        result shouldContain "query is required"
        verify(exactly = 0) { searchMessagesUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `returns error for invalid count`() {
        val result = tool.search_messages("deploy", 0, null)
        result shouldContain "count must be between 1 and 100"
        verify(exactly = 0) { searchMessagesUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `returns error for invalid page`() {
        val result = tool.search_messages("deploy", null, 0)
        result shouldContain "page must be at least 1"
        verify(exactly = 0) { searchMessagesUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `trims query before delegating`() {
        every { searchMessagesUseCase.execute("deploy", 5, 2) } returns
            SearchMessagesResult(ok = true, query = "deploy")

        val result = tool.search_messages(" deploy ", 5, 2)
        result shouldContain "\"ok\":true"
        verify { searchMessagesUseCase.execute("deploy", 5, 2) }
    }
}
