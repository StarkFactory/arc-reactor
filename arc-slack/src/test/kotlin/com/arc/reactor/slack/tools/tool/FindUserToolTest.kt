package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.FindUsersResult
import com.arc.reactor.slack.tools.client.SlackUser
import com.arc.reactor.slack.tools.usecase.FindUserUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class FindUserToolTest {

    private val findUserUseCase = mockk<FindUserUseCase>()
    private val tool = FindUserTool(findUserUseCase)

    @Test
    fun `finds users by partial name`() {
        every { findUserUseCase.execute("john", false, 10) } returns
            FindUsersResult(
                ok = true,
                query = "john",
                users = listOf(
                    SlackUser(id = "U123", name = "john", realName = "John Doe"),
                    SlackUser(id = "U124", name = "johnny", realName = "Johnny Doe")
                )
            )

        val result = tool.find_user("john", null, null)
        result shouldContain "\"ok\":true"
        result shouldContain "johnny"
    }

    @Test
    fun `returns error for blank query`() {
        val result = tool.find_user(" ", null, null)
        result shouldContain "query is required"
        verify(exactly = 0) { findUserUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `returns error for invalid limit`() {
        val result = tool.find_user("john", null, 0)
        result shouldContain "limit must be between 1 and 50"
        verify(exactly = 0) { findUserUseCase.execute(any(), any(), any()) }
    }

    @Test
    fun `trims query before delegating`() {
        every { findUserUseCase.execute("john", true, 5) } returns
            FindUsersResult(ok = true, query = "john", exactMatch = true)

        val result = tool.find_user(" john ", true, 5)
        result shouldContain "\"ok\":true"
        verify { findUserUseCase.execute("john", true, 5) }
    }
}
