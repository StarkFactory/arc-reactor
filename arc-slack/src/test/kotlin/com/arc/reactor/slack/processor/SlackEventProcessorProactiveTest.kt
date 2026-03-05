package com.arc.reactor.slack.processor

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.proactive.InMemoryProactiveChannelStore
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SlackEventProcessorProactiveTest {

    private val objectMapper = jacksonObjectMapper()
    private val eventHandler = mockk<SlackEventHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)

    private fun proactiveProperties(
        channelIds: List<String> = listOf("C_PROACTIVE"),
        maxConcurrent: Int = 2
    ) = SlackProperties(
        enabled = true,
        proactiveEnabled = true,
        proactiveChannelIds = channelIds,
        proactiveMaxConcurrent = maxConcurrent,
        maxConcurrentRequests = 5,
        failFastOnSaturation = true
    )

    private fun disabledProperties() = SlackProperties(
        enabled = true,
        proactiveEnabled = false,
        maxConcurrentRequests = 5,
        failFastOnSaturation = true
    )

    private fun buildProcessor(
        properties: SlackProperties,
        channelIds: List<String> = properties.proactiveChannelIds
    ): SlackEventProcessor {
        val store = InMemoryProactiveChannelStore()
        store.seedFromConfig(channelIds)
        return SlackEventProcessor(
            eventHandler, messagingService, metricsRecorder, properties,
            proactiveChannelStore = store
        )
    }

    private fun channelMessagePayload(
        channel: String = "C_PROACTIVE",
        user: String = "U1",
        text: String = "How do I deploy to staging?"
    ) = objectMapper.readTree(
        """{"type":"event_callback","event":{"type":"message","user":"$user",""" +
            """"channel":"$channel","text":"$text","ts":"1000.001"}}"""
    )

    @Nested
    inner class ProactiveRouting {

        @Test
        fun `dispatches to handleChannelMessage for proactive-enabled channel`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { eventHandler.handleChannelMessage(any()) } coAnswers {
                latch.countDown()
                false
            }
            val processor = buildProcessor(proactiveProperties())

            processor.submitEventCallback(channelMessagePayload(), "events_api")

            latch.await(3, TimeUnit.SECONDS) shouldBe true
            coVerify { eventHandler.handleChannelMessage(match { it.channelId == "C_PROACTIVE" }) }
        }

        @Test
        fun `does not dispatch proactive for non-allowlisted channel`() = runTest {
            val processor = buildProcessor(proactiveProperties(), channelIds = listOf("C_OTHER"))

            processor.submitEventCallback(
                channelMessagePayload(channel = "C_NOT_LISTED"), "events_api"
            )
            Thread.sleep(500)

            coVerify(exactly = 0) { eventHandler.handleChannelMessage(any()) }
        }

        @Test
        fun `does not dispatch proactive when feature is disabled`() = runTest {
            val processor = buildProcessor(disabledProperties())

            processor.submitEventCallback(channelMessagePayload(), "events_api")
            Thread.sleep(500)

            coVerify(exactly = 0) { eventHandler.handleChannelMessage(any()) }
        }

        @Test
        fun `does not dispatch proactive for DM channel type`() = runTest {
            val payload = objectMapper.readTree(
                """{"type":"event_callback","event":{"type":"message","user":"U1",""" +
                    """"channel":"C_PROACTIVE","text":"hello","ts":"1.0","channel_type":"im"}}"""
            )
            val processor = buildProcessor(proactiveProperties())

            processor.submitEventCallback(payload, "events_api")
            Thread.sleep(500)

            coVerify(exactly = 0) { eventHandler.handleChannelMessage(any()) }
        }
    }

    @Nested
    inner class ProactiveConcurrencyLimit {

        @Test
        fun `drops proactive evaluation when concurrency limit reached`() = runTest {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1)
            coEvery { eventHandler.handleChannelMessage(any()) } coAnswers {
                acquiredLatch.countDown()
                holdLatch.await(5, TimeUnit.SECONDS)
                false
            }

            val processor = buildProcessor(proactiveProperties(maxConcurrent = 1))

            // First message holds the semaphore
            processor.submitEventCallback(channelMessagePayload(user = "U1"), "events_api")
            acquiredLatch.await(3, TimeUnit.SECONDS) shouldBe true

            // Second message should be dropped
            processor.submitEventCallback(channelMessagePayload(user = "U2"), "events_api")
            Thread.sleep(500)

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "events_api",
                    reason = "proactive_concurrency",
                    eventType = "message"
                )
            }

            holdLatch.countDown()
        }
    }

    @Nested
    inner class ProactiveMetrics {

        @Test
        fun `records proactive handler success metric`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { eventHandler.handleChannelMessage(any()) } coAnswers {
                latch.countDown()
                true
            }

            val processor = buildProcessor(proactiveProperties())
            processor.submitEventCallback(channelMessagePayload(), "events_api")

            latch.await(3, TimeUnit.SECONDS) shouldBe true
            Thread.sleep(200)
            verify {
                metricsRecorder.recordHandler(
                    entrypoint = "events_api",
                    eventType = "proactive",
                    success = true,
                    durationMs = any()
                )
            }
        }

        @Test
        fun `records proactive handler declined metric`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { eventHandler.handleChannelMessage(any()) } coAnswers {
                latch.countDown()
                false
            }

            val processor = buildProcessor(proactiveProperties())
            processor.submitEventCallback(channelMessagePayload(), "events_api")

            latch.await(3, TimeUnit.SECONDS) shouldBe true
            Thread.sleep(200)
            verify {
                metricsRecorder.recordHandler(
                    entrypoint = "events_api",
                    eventType = "proactive",
                    success = false,
                    durationMs = any()
                )
            }
        }
    }
}
