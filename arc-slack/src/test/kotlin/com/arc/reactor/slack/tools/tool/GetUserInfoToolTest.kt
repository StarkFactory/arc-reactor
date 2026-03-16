package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.SlackUser
import com.arc.reactor.slack.tools.client.UserInfoResult
import com.arc.reactor.slack.tools.usecase.GetUserInfoUseCase
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class GetUserInfoToolTest {

    private val getUserInfoUseCase = mockk<GetUserInfoUseCase>()
    private val tool = GetUserInfoTool(getUserInfoUseCase)

    @Test
    fun `user info successfully를 반환한다`() {
        every { getUserInfoUseCase.execute("U123") } returns UserInfoResult(
            ok = true,
            user = SlackUser(
                id = "U123",
                name = "john",
                realName = "John Doe",
                displayName = "johndoe",
                email = "john@example.com",
                isBot = false
            )
        )

        val result = tool.get_user_info("U123")
        result shouldContain "\"ok\":true"
        result shouldContain "john"
        result shouldContain "John Doe"
        result shouldContain "john@example.com"
    }

    @Test
    fun `bot user correctly를 반환한다`() {
        every { getUserInfoUseCase.execute("U456") } returns UserInfoResult(
            ok = true,
            user = SlackUser(
                id = "U456",
                name = "bot",
                realName = "Bot User",
                isBot = true
            )
        )

        val result = tool.get_user_info("U456")
        result shouldContain "\"isBot\":true"
    }

    @Test
    fun `blank userId에 대해 error를 반환한다`() {
        val result = tool.get_user_info("")
        result shouldContain "error"
        result shouldContain "userId must be a valid Slack user ID"
    }

    @Test
    fun `API error를 처리한다`() {
        every { getUserInfoUseCase.execute("U999") } returns UserInfoResult(
            ok = false, error = "user_not_found"
        )

        val result = tool.get_user_info("U999")
        result shouldContain "\"ok\":false"
        result shouldContain "user_not_found"
    }

    @Test
    fun `null user on error를 반환한다`() {
        every { getUserInfoUseCase.execute("U999") } returns UserInfoResult(
            ok = false, user = null, error = "user_not_found"
        )

        val result = tool.get_user_info("U999")
        result shouldNotContain "\"realName\""
    }

    @Test
    fun `invalid userId format에 대해 error를 반환한다`() {
        val result = tool.get_user_info("123")
        result shouldContain "userId must be a valid Slack user ID"
    }

    @Test
    fun `userId before delegating를 정규화한다`() {
        every { getUserInfoUseCase.execute("U123") } returns UserInfoResult(ok = true, user = SlackUser(id = "U123", name = "john"))

        val result = tool.get_user_info(" U123 ")

        result shouldContain "\"ok\":true"
    }
}
