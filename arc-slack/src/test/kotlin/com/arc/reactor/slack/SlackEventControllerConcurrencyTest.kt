package com.arc.reactor.slack

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.controller.SlackEventController
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SlackEventControllerConcurrencyTest {

    private val objectMapper = jacksonObjectMapper()
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)

    private fun mentionPayload(index: Int): String = """
        {
            "type": "event_callback",
            "event": {
                "type": "app_mention",
                "user": "U$index",
                "channel": "C456",
                "text": "<@BOT> event $index",
                "ts": "1234.$index"
            }
        }
    """.trimIndent()

    @Nested
    inner class SemaphoreLimiting {

        @Test
        fun `limits concurrent processing to maxConcurrentRequests`() {
            val totalEvents = 6
            val maxConcurrentLimit = 2
            val maxConcurrent = AtomicInteger(0)
            val currentConcurrent = AtomicInteger(0)
            val latch = CountDownLatch(totalEvents)

            val handler = mockk<SlackEventHandler>(relaxed = true)
            coEvery { handler.handleAppMention(any()) } coAnswers {
                val current = currentConcurrent.incrementAndGet()
                maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                delay(300)
                currentConcurrent.decrementAndGet()
                latch.countDown()
            }

            val properties = SlackProperties(enabled = true, maxConcurrentRequests = maxConcurrentLimit)
            val controller = SlackEventController(objectMapper, handler, messagingService, properties)

            runBlocking {
                repeat(totalEvents) { i -> controller.handleEvent(mentionPayload(i)) }
            }

            latch.await(10, TimeUnit.SECONDS) shouldBe true
            maxConcurrent.get() shouldBeLessThanOrEqual maxConcurrentLimit
        }

        @Test
        fun `all events complete despite semaphore queuing`() {
            val totalEvents = 8
            val processedCount = AtomicInteger(0)
            val latch = CountDownLatch(totalEvents)

            val handler = mockk<SlackEventHandler>(relaxed = true)
            coEvery { handler.handleAppMention(any()) } coAnswers {
                delay(50)
                processedCount.incrementAndGet()
                latch.countDown()
            }

            val properties = SlackProperties(enabled = true, maxConcurrentRequests = 3)
            val controller = SlackEventController(objectMapper, handler, messagingService, properties)

            runBlocking {
                repeat(totalEvents) { i -> controller.handleEvent(mentionPayload(i)) }
            }

            latch.await(10, TimeUnit.SECONDS) shouldBe true
            processedCount.get() shouldBe totalEvents
        }
    }

    @Nested
    inner class ErrorIsolation {

        @Test
        fun `handler exception does not affect other events`() {
            val successCount = AtomicInteger(0)
            val totalEvents = 4

            val handler = mockk<SlackEventHandler>(relaxed = true)
            coEvery { handler.handleAppMention(any()) } coAnswers {
                val userId = firstArg<SlackEventCommand>().userId
                if (userId == "U0") throw RuntimeException("Simulated failure")
                successCount.incrementAndGet()
            }

            val properties = SlackProperties(enabled = true, maxConcurrentRequests = totalEvents)
            val controller = SlackEventController(objectMapper, handler, messagingService, properties)

            runBlocking {
                repeat(totalEvents) { i -> controller.handleEvent(mentionPayload(i)) }
                delay(500)
            }

            successCount.get() shouldBe (totalEvents - 1)
        }
    }
}
