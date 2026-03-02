package com.arc.reactor.slack.adapter

import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.service.SlackMessagingService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SlackMessageSenderAdapterTest {

    private val messagingService = mockk<SlackMessagingService>()
    private val adapter = SlackMessageSenderAdapter(messagingService)

    @Nested
    inner class SendMessage {

        @Test
        fun `calls SlackMessagingService sendMessage with correct parameters`() {
            coEvery { messagingService.sendMessage("C123", "hello") } returns
                SlackApiResult(ok = true)

            adapter.sendMessage("C123", "hello")

            coVerify(exactly = 1) { messagingService.sendMessage("C123", "hello") }
        }

        @Test
        fun `passes message text through without modification`() {
            val longMessage = "*[morning-briefing]* 브리핑:\n오늘 스프린트 요약입니다."
            coEvery { messagingService.sendMessage("C456", longMessage) } returns
                SlackApiResult(ok = true)

            adapter.sendMessage("C456", longMessage)

            coVerify(exactly = 1) { messagingService.sendMessage("C456", longMessage) }
        }

        @Test
        fun `does not throw when SlackApiResult ok is false`() {
            coEvery { messagingService.sendMessage("C123", "hello") } returns
                SlackApiResult(ok = false, error = "channel_not_found")

            assertDoesNotThrow {
                adapter.sendMessage("C123", "hello")
            }
        }

        @Test
        fun `propagates exception from SlackMessagingService`() {
            coEvery { messagingService.sendMessage("C123", "hello") } throws
                RuntimeException("Network error")

            org.junit.jupiter.api.assertThrows<RuntimeException> {
                adapter.sendMessage("C123", "hello")
            }
        }
    }
}
