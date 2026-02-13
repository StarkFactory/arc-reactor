package com.arc.reactor.slack

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.controller.SlackEventController
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.model.SlackChallengeResponse
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class SlackEventControllerTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val eventHandler = mockk<SlackEventHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val properties = SlackProperties(enabled = true, maxConcurrentRequests = 5)
    private val controller = SlackEventController(objectMapper, eventHandler, messagingService, properties)

    @Nested
    inner class UrlVerification {

        @Test
        fun `responds to URL verification challenge`() = runTest {
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
        fun `returns 200 immediately for app_mention`() = runTest {
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
        fun `dispatches app_mention to handler`() = runTest {
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
            delay(200) // Allow async processing

            coVerify(timeout = 2000) { eventHandler.handleAppMention(match { it.userId == "U123" }) }
        }

        @Test
        fun `deduplicates event callback by event_id`() = runTest {
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
            delay(200)

            coVerify(exactly = 1) { eventHandler.handleAppMention(any()) }
        }
    }

    @Nested
    inner class BotMessageFiltering {

        @Test
        fun `filters messages with bot_id`() = runTest {
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
            delay(200)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `filters messages with subtype`() = runTest {
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
            delay(200)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }
    }

    @Nested
    inner class ThreadMessageHandling {

        @Test
        fun `dispatches thread message to handler`() = runTest {
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
            delay(200)

            coVerify(timeout = 2000) {
                eventHandler.handleMessage(match {
                    it.threadTs == "1234.5678" && it.text == "follow-up question"
                })
            }
        }

        @Test
        fun `ignores non-thread messages`() = runTest {
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
            delay(200)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }
    }
}
