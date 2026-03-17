package com.arc.reactor.slack

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.controller.SlackEventController
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackChallengeResponse
import com.arc.reactor.slack.processor.SlackEventProcessor
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * [SlackEventController]의 이벤트 컨트롤러 테스트.
 *
 * URL 검증 챌린지, 이벤트 라우팅(app_mention/스레드 메시지),
 * event_id 중복 제거, 봇 메시지 필터링 등을 검증한다.
 */
class SlackEventControllerTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val eventHandler = mockk<SlackEventHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)
    private val properties = SlackProperties(enabled = true, maxConcurrentRequests = 5)
    private val eventProcessor = SlackEventProcessor(eventHandler, messagingService, metricsRecorder, properties)
    private val controller = SlackEventController(objectMapper, eventProcessor)

    @Nested
    inner class UrlVerification {

        @Test
        fun `responds은(는) to URL verification challenge`() = runTest {
            val payload = """{"type":"url_verification","challenge":"test-challenge-token"}"""

            val response = controller.handleEvent(payload)

            response.statusCode shouldBe HttpStatus.OK
            val body = response.body as SlackChallengeResponse
            body.challenge shouldBe "test-challenge-token"
        }
    }

    @Nested
    inner class EventRouting {

        @Test
        fun `app_mention에 대해 200 immediately를 반환한다`() = runTest {
            val payload = """
                {
                    "type": "event_callback",
                    "event": {
                        "type": "app_mention",
                        "user": "U123",
                        "channel": "C456",
                        "text": "<@BOT> hello",
                        "ts": "1234.5678"
                    }
                }
            """.trimIndent()

            val response = controller.handleEvent(payload)

            response.statusCode shouldBe HttpStatus.OK
        }

        @Test
        fun `app_mention to handler를 디스패치한다`() = runTest {
            coEvery { eventHandler.handleAppMention(any()) } returns Unit

            val payload = """
                {
                    "type": "event_callback",
                    "event": {
                        "type": "app_mention",
                        "user": "U123",
                        "channel": "C456",
                        "text": "<@BOT> what is 2+2?",
                        "ts": "1234.5678"
                    }
                }
            """.trimIndent()

            controller.handleEvent(payload)

            coVerify(timeout = 2000) { eventHandler.handleAppMention(match { it.userId == "U123" }) }
        }

        @Test
        fun `event callback by event_id를 중복 제거한다`() = runTest {
            coEvery { eventHandler.handleAppMention(any()) } returns Unit

            val payload = """
                {
                    "type": "event_callback",
                    "event_id": "Ev123",
                    "event": {
                        "type": "app_mention",
                        "user": "U123",
                        "channel": "C456",
                        "text": "<@BOT> hello",
                        "ts": "1234.5678"
                    }
                }
            """.trimIndent()

            controller.handleEvent(payload)
            controller.handleEvent(payload)

            coVerify(timeout = 2000, exactly = 1) { eventHandler.handleAppMention(any()) }
        }
    }

    @Nested
    inner class BotMessageFiltering {

        @Test
        fun `messages with bot_id를 필터링한다`() = runTest {
            val payload = """
                {
                    "type": "event_callback",
                    "event": {
                        "type": "message",
                        "user": "U123",
                        "channel": "C456",
                        "text": "bot message",
                        "ts": "1234.5678",
                        "bot_id": "B789"
                    }
                }
            """.trimIndent()

            controller.handleEvent(payload)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `messages with subtype를 필터링한다`() = runTest {
            val payload = """
                {
                    "type": "event_callback",
                    "event": {
                        "type": "message",
                        "user": "U123",
                        "channel": "C456",
                        "text": "edited message",
                        "ts": "1234.5678",
                        "subtype": "message_changed"
                    }
                }
            """.trimIndent()

            controller.handleEvent(payload)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }
    }

    @Nested
    inner class ThreadMessageHandling {

        @Test
        fun `thread message to handler를 디스패치한다`() = runTest {
            coEvery { eventHandler.handleMessage(any()) } returns Unit

            val payload = """
                {
                    "type": "event_callback",
                    "event": {
                        "type": "message",
                        "user": "U123",
                        "channel": "C456",
                        "text": "follow-up question",
                        "ts": "1234.9999",
                        "thread_ts": "1234.5678"
                    }
                }
            """.trimIndent()

            controller.handleEvent(payload)

            coVerify(timeout = 2000) {
                eventHandler.handleMessage(match {
                    it.threadTs == "1234.5678" && it.text == "follow-up question"
                })
            }
        }

        @Test
        fun `non-thread messages를 무시한다`() = runTest {
            val payload = """
                {
                    "type": "event_callback",
                    "event": {
                        "type": "message",
                        "user": "U123",
                        "channel": "C456",
                        "text": "just a message",
                        "ts": "1234.5678"
                    }
                }
            """.trimIndent()

            controller.handleEvent(payload)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }
    }
}
