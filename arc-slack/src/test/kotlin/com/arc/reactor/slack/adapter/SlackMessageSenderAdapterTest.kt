package com.arc.reactor.slack.adapter

import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.service.SlackMessagingService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [SlackMessageSenderAdapter]의 메시지 전송 어댑터 테스트.
 *
 * SlackMessagingService 위임 호출, 메시지 텍스트 무변경 전달,
 * API 실패 시 비-예외 동작, 예외 전파 등을 검증한다.
 */
class SlackMessageSenderAdapterTest {

    private val messagingService = mockk<SlackMessagingService>()
    private val adapter = SlackMessageSenderAdapter(messagingService)

    @Nested
    inner class SendMessage {

        @Test
        fun `SlackMessagingService sendMessage with correct parameters를 호출한다`() {
            coEvery { messagingService.sendMessage("C123", "hello") } returns
                SlackApiResult(ok = true)

            adapter.sendMessage("C123", "hello")

            coVerify(exactly = 1) { messagingService.sendMessage("C123", "hello") }
        }

        @Test
        fun `message text through without modification를 전달한다`() {
            val longMessage = "*[morning-briefing]* 브리핑:\n오늘 스프린트 요약입니다."
            coEvery { messagingService.sendMessage("C456", longMessage) } returns
                SlackApiResult(ok = true)

            adapter.sendMessage("C456", longMessage)

            coVerify(exactly = 1) { messagingService.sendMessage("C456", longMessage) }
        }

        @Test
        fun `does not throw when SlackApiResult ok은(는) false이다`() {
            coEvery { messagingService.sendMessage("C123", "hello") } returns
                SlackApiResult(ok = false, error = "channel_not_found")

            assertDoesNotThrow {
                adapter.sendMessage("C123", "hello")
            }
        }

        @Test
        fun `exception from SlackMessagingService를 전파한다`() {
            coEvery { messagingService.sendMessage("C123", "hello") } throws
                RuntimeException("Network error")

            org.junit.jupiter.api.assertThrows<RuntimeException> {
                adapter.sendMessage("C123", "hello")
            }
        }
    }
}
