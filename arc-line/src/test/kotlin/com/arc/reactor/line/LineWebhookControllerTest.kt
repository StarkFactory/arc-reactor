package com.arc.reactor.line

import com.arc.reactor.line.config.LineProperties
import com.arc.reactor.line.controller.LineWebhookController
import com.arc.reactor.line.handler.LineEventHandler
import com.arc.reactor.line.model.LineEventCommand
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

class LineWebhookControllerTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val eventHandler = mockk<LineEventHandler>(relaxed = true)
    private val properties = LineProperties(enabled = true, maxConcurrentRequests = 5)
    private val controller = LineWebhookController(objectMapper, eventHandler, properties)

    @Nested
    inner class MessageEventRouting {

        @Test
        fun `returns 200 immediately for text message event`() = runTest {
            val payload = """
                {
                    "events": [{
                        "type": "message",
                        "replyToken": "tok-1",
                        "source": {"type": "user", "userId": "U123"},
                        "message": {"type": "text", "id": "msg-1", "text": "hello"}
                    }]
                }
            """.trimIndent()

            val response = controller.handleWebhook(payload)

            response.statusCode shouldBe HttpStatus.OK
        }

        @Test
        fun `dispatches text message event to handler`() = runTest {
            coEvery { eventHandler.handleMessage(any()) } returns Unit

            val payload = """
                {
                    "events": [{
                        "type": "message",
                        "replyToken": "tok-1",
                        "source": {"type": "user", "userId": "U123"},
                        "message": {"type": "text", "id": "msg-1", "text": "what is 2+2?"}
                    }]
                }
            """.trimIndent()

            controller.handleWebhook(payload)

            coVerify(timeout = 2000) {
                eventHandler.handleMessage(
                    match { it.userId == "U123" && it.text == "what is 2+2?" }
                )
            }
        }

        @Test
        fun `dispatches multiple events from single webhook`() = runTest {
            coEvery { eventHandler.handleMessage(any()) } returns Unit

            val payload = """
                {
                    "events": [
                        {
                            "type": "message",
                            "replyToken": "tok-1",
                            "source": {"type": "user", "userId": "U001"},
                            "message": {"type": "text", "id": "m1", "text": "first"}
                        },
                        {
                            "type": "message",
                            "replyToken": "tok-2",
                            "source": {"type": "user", "userId": "U002"},
                            "message": {"type": "text", "id": "m2", "text": "second"}
                        }
                    ]
                }
            """.trimIndent()

            controller.handleWebhook(payload)

            coVerify(timeout = 2000) {
                eventHandler.handleMessage(match { it.userId == "U001" })
            }
            coVerify(timeout = 2000) {
                eventHandler.handleMessage(match { it.userId == "U002" })
            }
        }
    }

    @Nested
    inner class NonTextMessageFiltering {

        @Test
        fun `ignores image message events`() = runTest {
            val payload = """
                {
                    "events": [{
                        "type": "message",
                        "replyToken": "tok-1",
                        "source": {"type": "user", "userId": "U123"},
                        "message": {"type": "image", "id": "img-1"}
                    }]
                }
            """.trimIndent()

            controller.handleWebhook(payload)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `ignores sticker message events`() = runTest {
            val payload = """
                {
                    "events": [{
                        "type": "message",
                        "replyToken": "tok-1",
                        "source": {"type": "user", "userId": "U123"},
                        "message": {"type": "sticker", "id": "stk-1"}
                    }]
                }
            """.trimIndent()

            controller.handleWebhook(payload)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `ignores non-message event types`() = runTest {
            val payload = """
                {
                    "events": [{
                        "type": "follow",
                        "replyToken": "tok-1",
                        "source": {"type": "user", "userId": "U123"}
                    }]
                }
            """.trimIndent()

            controller.handleWebhook(payload)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }
    }

    @Nested
    inner class EmptyPayload {

        @Test
        fun `handles empty events array`() = runTest {
            val payload = """{"events": []}"""

            val response = controller.handleWebhook(payload)

            response.statusCode shouldBe HttpStatus.OK
            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }
    }
}
