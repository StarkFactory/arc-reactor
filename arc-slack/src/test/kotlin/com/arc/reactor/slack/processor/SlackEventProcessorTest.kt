package com.arc.reactor.slack.processor

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [SlackEventProcessor].
 *
 * Covers event routing, deduplication, backpressure (fail-fast and queue modes),
 * bot/subtype filtering, notification-on-drop, and error isolation.
 */
class SlackEventProcessorTest {

    private val objectMapper = jacksonObjectMapper()
    private val eventHandler = mockk<SlackEventHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)

    private fun buildProcessor(properties: SlackProperties): SlackEventProcessor =
        SlackEventProcessor(eventHandler, messagingService, metricsRecorder, properties)

    private fun defaultProperties(
        maxConcurrentRequests: Int = 5,
        failFastOnSaturation: Boolean = true,
        notifyOnDrop: Boolean = false,
        requestTimeoutMs: Long = 5000,
        eventDedupEnabled: Boolean = true
    ) = SlackProperties(
        enabled = true,
        maxConcurrentRequests = maxConcurrentRequests,
        failFastOnSaturation = failFastOnSaturation,
        notifyOnDrop = notifyOnDrop,
        requestTimeoutMs = requestTimeoutMs,
        eventDedupEnabled = eventDedupEnabled
    )

    private fun mentionPayload(
        user: String = "U123",
        channel: String = "C456",
        text: String = "hello",
        ts: String = "1000.001",
        eventId: String? = null,
        retryNum: String? = null,
        retryReason: String? = null
    ) = Triple(
        objectMapper.readTree(
            buildString {
                append("""{"type":"event_callback",""")
                if (eventId != null) append(""""event_id":"$eventId",""")
                append(
                    """"event":{"type":"app_mention","user":"$user","channel":"$channel",""" +
                        """"text":"$text","ts":"$ts"}}"""
                )
            }
        ),
        retryNum,
        retryReason
    )

    private fun threadMessagePayload(
        user: String = "U123",
        channel: String = "C456",
        threadTs: String = "1000.000"
    ) = objectMapper.readTree(
        """{"type":"event_callback","event":{"type":"message","user":"$user",""" +
            """"channel":"$channel","text":"reply","ts":"1000.999","thread_ts":"$threadTs"}}"""
    )

    private fun botMessagePayload() = objectMapper.readTree(
        """{"type":"event_callback","event":{"type":"message","user":"U1","channel":"C1",""" +
            """"text":"bot msg","ts":"1.0","bot_id":"B1"}}"""
    )

    private fun subtypeMessagePayload() = objectMapper.readTree(
        """{"type":"event_callback","event":{"type":"message","user":"U1","channel":"C1",""" +
            """"text":"edited","ts":"1.0","subtype":"message_changed"}}"""
    )

    private fun noUserPayload() = objectMapper.readTree(
        """{"type":"event_callback","event":{"type":"app_mention","user":"","channel":"C1","text":"x","ts":"1.0"}}"""
    )

    // =========================================================================
    // Event routing
    // =========================================================================

    @Nested
    inner class EventRouting {

        @Test
        fun `app_mention dispatches to handleAppMention`() = runTest {
            val processor = buildProcessor(defaultProperties())
            val (payload, retryNum, retryReason) = mentionPayload()

            processor.submitEventCallback(payload, "events_api", retryNum, retryReason)

            coVerify(timeout = 2000) {
                eventHandler.handleAppMention(match { it.userId == "U123" && it.eventType == "app_mention" })
            }
        }

        @Test
        fun `thread message dispatches to handleMessage`() = runTest {
            val processor = buildProcessor(defaultProperties())
            val payload = threadMessagePayload()

            processor.submitEventCallback(payload, "events_api")

            coVerify(timeout = 2000) {
                eventHandler.handleMessage(match { it.threadTs == "1000.000" })
            }
        }

        @Test
        fun `non-thread message does not dispatch to handleMessage`() = runTest {
            val payload = objectMapper.readTree(
                """{"type":"event_callback","event":{"type":"message","user":"U1","channel":"C1",""" +
                    """"text":"top-level","ts":"1.0"}}"""
            )
            val processor = buildProcessor(defaultProperties())

            processor.submitEventCallback(payload, "events_api")
            delay(300)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `unknown event type does not dispatch to any handler`() = runTest {
            val payload = objectMapper.readTree(
                """{"type":"event_callback","event":{"type":"reaction_added","user":"U1","channel":"C1","ts":"1.0"}}"""
            )
            val processor = buildProcessor(defaultProperties())

            processor.submitEventCallback(payload, "events_api")
            delay(300)

            coVerify(exactly = 0) { eventHandler.handleAppMention(any()) }
            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }
    }

    // =========================================================================
    // Inbound metrics
    // =========================================================================

    @Nested
    inner class InboundMetrics {

        @Test
        fun `recordInbound is called with correct entrypoint and eventType`() {
            val processor = buildProcessor(defaultProperties())
            val (payload, _, _) = mentionPayload()

            processor.submitEventCallback(payload, "events_api")

            verify { metricsRecorder.recordInbound(entrypoint = "events_api", eventType = "app_mention") }
        }

        @Test
        fun `recordHandler success metric is emitted after successful processing`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers { latch.countDown() }
            val processor = buildProcessor(defaultProperties())
            val (payload, _, _) = mentionPayload()

            processor.submitEventCallback(payload, "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            delay(100) // allow recordHandler call to be dispatched after handler completes
            coVerify {
                metricsRecorder.recordHandler(
                    entrypoint = "events_api",
                    eventType = "app_mention",
                    success = true,
                    durationMs = any()
                )
            }
        }

        @Test
        fun `recordHandler failure metric is emitted when handler throws`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                latch.countDown()
                throw RuntimeException("handler failure")
            }
            val processor = buildProcessor(defaultProperties())
            val (payload, _, _) = mentionPayload()

            processor.submitEventCallback(payload, "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            delay(100)
            coVerify {
                metricsRecorder.recordHandler(
                    entrypoint = "events_api",
                    eventType = "app_mention",
                    success = false,
                    durationMs = any()
                )
            }
        }
    }

    // =========================================================================
    // Bot and subtype filtering
    // =========================================================================

    @Nested
    inner class BotAndSubtypeFiltering {

        @Test
        fun `messages with bot_id are silently dropped`() = runTest {
            val processor = buildProcessor(defaultProperties())

            processor.submitEventCallback(botMessagePayload(), "events_api")
            delay(300)

            coVerify(exactly = 0) { eventHandler.handleAppMention(any()) }
            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `messages with subtype are silently dropped`() = runTest {
            val processor = buildProcessor(defaultProperties())

            processor.submitEventCallback(subtypeMessagePayload(), "events_api")
            delay(300)

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `events missing userId are skipped before dispatch`() = runTest {
            val processor = buildProcessor(defaultProperties())

            processor.submitEventCallback(noUserPayload(), "events_api")
            delay(300)

            coVerify(exactly = 0) { eventHandler.handleAppMention(any()) }
        }
    }

    // =========================================================================
    // Deduplication
    // =========================================================================

    @Nested
    inner class Deduplication {

        @Test
        fun `duplicate event_id causes second event to be skipped`() = runTest {
            val processor = buildProcessor(defaultProperties(eventDedupEnabled = true))
            val (payload, _, _) = mentionPayload(eventId = "Ev-dup-001")

            processor.submitEventCallback(payload, "events_api")
            processor.submitEventCallback(payload, "events_api")

            coVerify(timeout = 2000, exactly = 1) { eventHandler.handleAppMention(any()) }
        }

        @Test
        fun `duplicate is recorded in metrics`() {
            val processor = buildProcessor(defaultProperties(eventDedupEnabled = true))
            val (payload, _, _) = mentionPayload(eventId = "Ev-dup-002")

            processor.submitEventCallback(payload, "events_api")
            processor.submitEventCallback(payload, "events_api")

            verify { metricsRecorder.recordDuplicate(eventType = "app_mention") }
        }

        @Test
        fun `different event_ids are both processed`() = runTest {
            val processor = buildProcessor(defaultProperties(eventDedupEnabled = true))
            val (payload1, _, _) = mentionPayload(eventId = "Ev-A", user = "U1")
            val (payload2, _, _) = mentionPayload(eventId = "Ev-B", user = "U2")

            processor.submitEventCallback(payload1, "events_api")
            processor.submitEventCallback(payload2, "events_api")

            coVerify(timeout = 2000, exactly = 2) { eventHandler.handleAppMention(any()) }
        }

        @Test
        fun `event without event_id is never considered duplicate`() = runTest {
            val processor = buildProcessor(defaultProperties(eventDedupEnabled = true))
            val (payload, _, _) = mentionPayload(eventId = null, user = "U_no_id")

            processor.submitEventCallback(payload, "events_api")
            processor.submitEventCallback(payload, "events_api")

            coVerify(timeout = 2000, exactly = 2) { eventHandler.handleAppMention(any()) }
        }
    }

    // =========================================================================
    // Retry metadata forwarding
    // =========================================================================

    @Nested
    inner class RetryMetadataForwarding {

        @Test
        fun `retry headers are logged but processing still proceeds`() = runTest {
            val processor = buildProcessor(defaultProperties())
            val (payload, _, _) = mentionPayload(retryNum = "1", retryReason = "http_timeout")

            processor.submitEventCallback(
                payload = payload,
                entrypoint = "events_api",
                retryNum = "1",
                retryReason = "http_timeout"
            )

            coVerify(timeout = 2000) { eventHandler.handleAppMention(any()) }
        }
    }

    // =========================================================================
    // Backpressure — fail-fast mode
    // =========================================================================

    @Nested
    inner class BackpressureFailFastMode {

        @Test
        fun `events are dropped when semaphore is saturated in fail-fast mode`() = runTest {
            val processedCount = AtomicInteger(0)
            val holdLatch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                processedCount.incrementAndGet()
                holdLatch.await(5, TimeUnit.SECONDS) // hold semaphore
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = true)
            )

            // First event holds the semaphore
            val (p1, _, _) = mentionPayload(user = "U1")
            processor.submitEventCallback(p1, "events_api")
            delay(100) // let first event acquire permit

            // Subsequent events must be dropped
            repeat(3) { i ->
                val (p, _, _) = mentionPayload(user = "U${i + 2}")
                processor.submitEventCallback(p, "events_api")
            }

            holdLatch.countDown()
            delay(300) // let processing settle

            processedCount.get() shouldBe 1
        }

        @Test
        fun `recordDropped with queue_overflow is emitted for fail-fast rejections`() = runTest {
            val holdLatch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                holdLatch.await(5, TimeUnit.SECONDS)
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = true)
            )
            val (p1, _, _) = mentionPayload(user = "U1")
            processor.submitEventCallback(p1, "events_api")
            delay(100) // semaphore acquired by first event

            val (p2, _, _) = mentionPayload(user = "U2")
            processor.submitEventCallback(p2, "events_api")
            holdLatch.countDown()

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "events_api",
                    reason = "queue_overflow",
                    eventType = "app_mention"
                )
            }
        }

        @Test
        fun `notifyOnDrop sends busy message to channel when enabled`() = runTest {
            val holdLatch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                holdLatch.await(5, TimeUnit.SECONDS)
            }
            coEvery { messagingService.sendMessage(any(), any(), any()) } returns
                com.arc.reactor.slack.model.SlackApiResult(ok = true)

            val processor = buildProcessor(
                defaultProperties(
                    maxConcurrentRequests = 1,
                    failFastOnSaturation = true,
                    notifyOnDrop = true
                )
            )
            val (p1, _, _) = mentionPayload(user = "U1", ts = "1000.001")
            processor.submitEventCallback(p1, "events_api")
            delay(100)

            val (p2, _, _) = mentionPayload(user = "U2", ts = "1000.002")
            processor.submitEventCallback(p2, "events_api")

            holdLatch.countDown()

            coVerify(timeout = 3000) {
                messagingService.sendMessage(
                    channelId = "C456",
                    text = match { it.contains("busy", ignoreCase = true) },
                    threadTs = any()
                )
            }
        }

        @Test
        fun `notifyOnDrop is suppressed when disabled`() = runTest {
            val holdLatch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                holdLatch.await(5, TimeUnit.SECONDS)
            }

            val processor = buildProcessor(
                defaultProperties(
                    maxConcurrentRequests = 1,
                    failFastOnSaturation = true,
                    notifyOnDrop = false
                )
            )
            val (p1, _, _) = mentionPayload(user = "U1")
            processor.submitEventCallback(p1, "events_api")
            delay(100)

            val (p2, _, _) = mentionPayload(user = "U2")
            processor.submitEventCallback(p2, "events_api")
            holdLatch.countDown()
            delay(300)

            coVerify(exactly = 0) { messagingService.sendMessage(any(), any(), any()) }
        }
    }

    // =========================================================================
    // Backpressure — queue mode with timeout
    // =========================================================================

    @Nested
    inner class BackpressureQueueModeTimeout {

        @Test
        fun `events wait for permit in queue mode when capacity is available`() = runTest {
            val processedCount = AtomicInteger(0)
            val latch = CountDownLatch(2)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                processedCount.incrementAndGet()
                latch.countDown()
            }

            val processor = buildProcessor(
                defaultProperties(
                    maxConcurrentRequests = 1,
                    failFastOnSaturation = false,
                    requestTimeoutMs = 5000
                )
            )
            val (p1, _, _) = mentionPayload(user = "U1")
            val (p2, _, _) = mentionPayload(user = "U2")

            processor.submitEventCallback(p1, "events_api")
            processor.submitEventCallback(p2, "events_api")

            latch.await(10, TimeUnit.SECONDS) shouldBe true
            processedCount.get() shouldBe 2
        }

        @Test
        fun `event is dropped with queue_timeout when requestTimeoutMs is very small`() {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1) // signals when first handler holds semaphore
            runBlocking {
                coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                    acquiredLatch.countDown()
                    holdLatch.await(5, TimeUnit.SECONDS)
                }

                val processor = buildProcessor(
                    defaultProperties(
                        maxConcurrentRequests = 1,
                        failFastOnSaturation = false,
                        requestTimeoutMs = 200
                    )
                )
                val (p1, _, _) = mentionPayload(user = "U1")
                processor.submitEventCallback(p1, "events_api")
                acquiredLatch.await(5, TimeUnit.SECONDS) // first handler confirmed to hold semaphore

                val (p2, _, _) = mentionPayload(user = "U2")
                processor.submitEventCallback(p2, "events_api")
            }
            Thread.sleep(800) // wait for 200ms timeout + Dispatchers.Default scheduling margin
            holdLatch.countDown()

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "events_api",
                    reason = "queue_timeout",
                    eventType = "app_mention"
                )
            }
        }
    }

    // =========================================================================
    // Error isolation
    // =========================================================================

    @Nested
    inner class ErrorIsolation {

        @Test
        fun `handler exception for one event does not prevent processing of others`() = runTest {
            val successCount = AtomicInteger(0)
            val latch = CountDownLatch(3) // 3 successful events expected
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                val userId = firstArg<SlackEventCommand>().userId
                if (userId == "U1") throw RuntimeException("Simulated failure for U1")
                successCount.incrementAndGet()
                latch.countDown()
            }

            val processor = buildProcessor(defaultProperties(maxConcurrentRequests = 5))
            listOf("U1", "U2", "U3", "U4").forEach { user ->
                val (payload, _, _) = mentionPayload(user = user)
                processor.submitEventCallback(payload, "events_api")
            }

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            successCount.get() shouldBe 3
        }

        @Test
        fun `semaphore is always released even when handler throws`() = runTest {
            coEvery { eventHandler.handleAppMention(any()) } throws RuntimeException("forced error")

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = false)
            )

            val (p1, _, _) = mentionPayload(user = "U1")
            processor.submitEventCallback(p1, "events_api")
            delay(300) // wait for first handler call and release

            // If semaphore was not released, this second event would time out
            val processedSecond = AtomicInteger(0)
            val latch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                processedSecond.incrementAndGet()
                latch.countDown()
            }
            val (p2, _, _) = mentionPayload(user = "U2")
            processor.submitEventCallback(p2, "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            processedSecond.get() shouldBe 1
        }
    }
}
