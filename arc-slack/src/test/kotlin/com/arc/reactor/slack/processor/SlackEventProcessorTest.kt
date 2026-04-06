package com.arc.reactor.slack.processor

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.arc.reactor.support.AsyncTestSupport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SlackEventProcessor]의 이벤트 처리 테스트.
 *
 * 이벤트 라우팅, 중복 제거, 백프레셔(fail-fast 및 큐 모드),
 * 봇/서브타입 필터링, 드롭 시 알림, 오류 격리를 검증한다.
 */
class SlackEventProcessorTest {

    private val objectMapper = jacksonObjectMapper()
    private val eventHandler = mockk<SlackEventHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)

    private fun buildProcessor(
        properties: SlackProperties,
        threadTracker: SlackThreadTracker? = null
    ): SlackEventProcessor =
        SlackEventProcessor(eventHandler, messagingService, metricsRecorder, properties, threadTracker)

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

    private fun topLevelMessagePayload(
        user: String = "U1",
        channel: String = "C1",
        channelType: String? = null
    ) = objectMapper.readTree(
        objectMapper.writeValueAsString(
            buildMap<String, Any> {
                put("type", "event_callback")
                put(
                    "event",
                    buildMap<String, String> {
                        put("type", "message")
                        put("user", user)
                        put("channel", channel)
                        put("text", "top-level")
                        put("ts", "1.0")
                        if (channelType != null) {
                            put("channel_type", channelType)
                        }
                    }
                )
            }
        )
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
    // 이벤트 라우팅
    // =========================================================================

    @Nested
    inner class EventRouting {

        @Test
        fun `app_mention은(는) handleAppMention로 디스패치한다`() = runTest {
            val processor = buildProcessor(defaultProperties())
            val (payload, retryNum, retryReason) = mentionPayload()

            processor.submitEventCallback(payload, "events_api", retryNum, retryReason)

            coVerify(timeout = 2000) {
                eventHandler.handleAppMention(match { it.userId == "U123" && it.eventType == "app_mention" })
            }
        }

        @Test
        fun `thread은(는) message dispatches to handleMessage`() = runTest {
            val processor = buildProcessor(defaultProperties())
            val payload = threadMessagePayload()

            processor.submitEventCallback(payload, "events_api")

            coVerify(timeout = 2000) {
                eventHandler.handleMessage(match { it.threadTs == "1000.000" })
            }
        }

        @Test
        fun `tracker is enabled and thread is untracked일 때 thread message은(는) ignored이다`() = runTest {
            val tracker = SlackThreadTracker()
            val processor = buildProcessor(defaultProperties(), tracker)
            val payload = threadMessagePayload(threadTs = "2000.000")

            processor.submitEventCallback(payload, "events_api")
            AsyncTestSupport.settleBackground()

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "events_api",
                    reason = "untracked_thread",
                    eventType = "message"
                )
            }
        }

        @Test
        fun `tracker contains thread일 때 thread message dispatches`() = runTest {
            val tracker = SlackThreadTracker()
            tracker.track("C456", "1000.000")
            val processor = buildProcessor(defaultProperties(), tracker)
            val payload = threadMessagePayload(threadTs = "1000.000")

            processor.submitEventCallback(payload, "events_api")

            coVerify(timeout = 2000) {
                eventHandler.handleMessage(match { it.threadTs == "1000.000" })
            }
        }

        @Test
        fun `비스레드 메시지는 handleMessage로 디스패치하지 않는다`() = runTest {
            val payload = topLevelMessagePayload()
            val processor = buildProcessor(defaultProperties())

            processor.submitEventCallback(payload, "events_api")
            AsyncTestSupport.settleBackground()

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `non-thread direct message dispatches when dm processing은(는) enabled이다`() = runTest {
            val payload = topLevelMessagePayload(channel = "D1", channelType = "im")
            val processor = buildProcessor(
                defaultProperties().copy(processDirectMessagesWithoutThread = true)
            )

            processor.submitEventCallback(payload, "events_api")

            coVerify(timeout = 2000) {
                eventHandler.handleMessage(match { it.channelId == "D1" && it.threadTs == null })
            }
        }

        @Test
        fun `non-thread channel message remains ignored when dm processing은(는) enabled이다`() = runTest {
            val payload = topLevelMessagePayload(channel = "C1", channelType = "channel")
            val processor = buildProcessor(
                defaultProperties().copy(processDirectMessagesWithoutThread = true)
            )

            processor.submitEventCallback(payload, "events_api")
            AsyncTestSupport.settleBackground()

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `알 수 없는 event type does not dispatch to any handler`() = runTest {
            val payload = objectMapper.readTree(
                """{"type":"event_callback","event":{"type":"reaction_added","user":"U1","channel":"C1","ts":"1.0"}}"""
            )
            val processor = buildProcessor(defaultProperties())

            processor.submitEventCallback(payload, "events_api")
            AsyncTestSupport.settleBackground()

            coVerify(exactly = 0) { eventHandler.handleAppMention(any()) }
            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }
    }

    // =========================================================================
    // 인바운드 메트릭
    // =========================================================================

    @Nested
    inner class InboundMetrics {

        @Test
        fun `recordInbound은(는) called with correct entrypoint and eventType이다`() {
            val processor = buildProcessor(defaultProperties())
            val (payload, _, _) = mentionPayload()

            processor.submitEventCallback(payload, "events_api")

            verify { metricsRecorder.recordInbound(entrypoint = "events_api", eventType = "app_mention") }
        }

        @Test
        fun `recordHandler success metric은(는) emitted after successful processing이다`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers { latch.countDown() }
            val processor = buildProcessor(defaultProperties())
            val (payload, _, _) = mentionPayload()

            processor.submitEventCallback(payload, "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            coVerify(timeout = 2000) {
                metricsRecorder.recordHandler(
                    entrypoint = "events_api",
                    eventType = "app_mention",
                    success = true,
                    durationMs = any()
                )
            }
        }

        @Test
        fun `handler throws일 때 recordHandler failure metric은(는) emitted이다`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                latch.countDown()
                throw RuntimeException("handler failure")
            }
            val processor = buildProcessor(defaultProperties())
            val (payload, _, _) = mentionPayload()

            processor.submitEventCallback(payload, "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            coVerify(timeout = 2000) {
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
    // 봇 및 서브타입 필터링
    // =========================================================================

    @Nested
    inner class BotAndSubtypeFiltering {

        @Test
        fun `messages with bot_id은(는) silently dropped이다`() = runTest {
            val processor = buildProcessor(defaultProperties())

            processor.submitEventCallback(botMessagePayload(), "events_api")
            AsyncTestSupport.settleBackground()

            coVerify(exactly = 0) { eventHandler.handleAppMention(any()) }
            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `messages with subtype은(는) silently dropped이다`() = runTest {
            val processor = buildProcessor(defaultProperties())

            processor.submitEventCallback(subtypeMessagePayload(), "events_api")
            AsyncTestSupport.settleBackground()

            coVerify(exactly = 0) { eventHandler.handleMessage(any()) }
        }

        @Test
        fun `dispatch전에 events missing userId are skipped`() = runTest {
            val processor = buildProcessor(defaultProperties())

            processor.submitEventCallback(noUserPayload(), "events_api")
            AsyncTestSupport.settleBackground()

            coVerify(exactly = 0) { eventHandler.handleAppMention(any()) }
        }
    }

    // =========================================================================
    // 중복 제거
    // =========================================================================

    @Nested
    inner class Deduplication {

        @Test
        fun `duplicate은(는) event_id causes second event to be skipped`() = runTest {
            val processor = buildProcessor(defaultProperties(eventDedupEnabled = true))
            val (payload, _, _) = mentionPayload(eventId = "Ev-dup-001")

            processor.submitEventCallback(payload, "events_api")
            processor.submitEventCallback(payload, "events_api")

            coVerify(timeout = 2000, exactly = 1) { eventHandler.handleAppMention(any()) }
        }

        @Test
        fun `duplicate은(는) recorded in metrics이다`() {
            val processor = buildProcessor(defaultProperties(eventDedupEnabled = true))
            val (payload, _, _) = mentionPayload(eventId = "Ev-dup-002")

            processor.submitEventCallback(payload, "events_api")
            processor.submitEventCallback(payload, "events_api")

            verify { metricsRecorder.recordDuplicate(eventType = "app_mention") }
        }

        @Test
        fun `different event_ids은(는) both processed이다`() = runTest {
            val processor = buildProcessor(defaultProperties(eventDedupEnabled = true))
            val (payload1, _, _) = mentionPayload(eventId = "Ev-A", user = "U1")
            val (payload2, _, _) = mentionPayload(eventId = "Ev-B", user = "U2")

            processor.submitEventCallback(payload1, "events_api")
            processor.submitEventCallback(payload2, "events_api")

            coVerify(timeout = 2000, exactly = 2) { eventHandler.handleAppMention(any()) }
        }

        @Test
        fun `event without event_id은(는) never considered duplicate이다`() = runTest {
            val processor = buildProcessor(defaultProperties(eventDedupEnabled = true))
            val (payload, _, _) = mentionPayload(eventId = null, user = "U_no_id")

            processor.submitEventCallback(payload, "events_api")
            processor.submitEventCallback(payload, "events_api")

            coVerify(timeout = 2000, exactly = 2) { eventHandler.handleAppMention(any()) }
        }
    }

    // =========================================================================
    // 재시도 메타데이터 전달
    // =========================================================================

    @Nested
    inner class RetryMetadataForwarding {

        @Test
        fun `retry headers은(는) logged but processing still proceeds이다`() = runTest {
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
    // 백프레셔 — fail-fast 모드
    // =========================================================================

    @Nested
    inner class BackpressureFailFastMode {

        @Test
        fun `events are dropped when semaphore은(는) saturated in fail-fast mode이다`() = runTest {
            val processedCount = AtomicInteger(0)
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                processedCount.incrementAndGet()
                acquiredLatch.countDown()
                holdLatch.await(5, TimeUnit.SECONDS) // hold semaphore
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = true)
            )

            // 첫 번째 이벤트가 세마포어를 보유
            val (p1, _, _) = mentionPayload(user = "U1")
            processor.submitEventCallback(p1, "events_api")
            acquiredLatch.await(5, TimeUnit.SECONDS) shouldBe true

            // 이후 이벤트는 삭제되어야 한다
            repeat(3) { i ->
                val (p, _, _) = mentionPayload(user = "U${i + 2}")
                processor.submitEventCallback(p, "events_api")
            }

            holdLatch.countDown()
            AsyncTestSupport.pollCount(processedCount, 1)

            processedCount.get() shouldBe 1
        }

        @Test
        fun `recordDropped with queue_overflow은(는) emitted for fail-fast rejections이다`() = runTest {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                acquiredLatch.countDown()
                holdLatch.await(5, TimeUnit.SECONDS)
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = true)
            )
            val (p1, _, _) = mentionPayload(user = "U1")
            processor.submitEventCallback(p1, "events_api")
            acquiredLatch.await(5, TimeUnit.SECONDS) shouldBe true

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
        fun `enabled일 때 notifyOnDrop sends busy message to channel`() = runTest {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                acquiredLatch.countDown()
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
            acquiredLatch.await(5, TimeUnit.SECONDS) shouldBe true

            val (p2, _, _) = mentionPayload(user = "U2", ts = "1000.002")
            processor.submitEventCallback(p2, "events_api")

            holdLatch.countDown()

            coVerify(timeout = 3000) {
                messagingService.sendMessage(
                    channelId = "C456",
                    text = match { it.contains(":hourglass:") },
                    threadTs = any()
                )
            }
        }

        @Test
        fun `disabled일 때 notifyOnDrop은(는) suppressed이다`() = runTest {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                acquiredLatch.countDown()
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
            acquiredLatch.await(5, TimeUnit.SECONDS) shouldBe true

            val (p2, _, _) = mentionPayload(user = "U2")
            processor.submitEventCallback(p2, "events_api")
            holdLatch.countDown()
            AsyncTestSupport.settleBackground()

            coVerify(exactly = 0) { messagingService.sendMessage(any(), any(), any()) }
        }
    }

    // =========================================================================
    // 백프레셔 — 타임아웃이 있는 큐 모드
    // =========================================================================

    @Nested
    inner class BackpressureQueueModeTimeout {

        @Test
        fun `events wait for permit in queue mode when capacity은(는) available이다`() = runTest {
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
        fun `requestTimeoutMs is very small일 때 event은(는) dropped with queue_timeout이다`() {
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

            verify(timeout = 3000) {
                metricsRecorder.recordDropped(
                    entrypoint = "events_api",
                    reason = "queue_timeout",
                    eventType = "app_mention"
                )
            }
            holdLatch.countDown()
        }
    }

    // =========================================================================
    // 오류 격리
    // =========================================================================

    @Nested
    inner class ErrorIsolation {

        @Test
        fun `handler은(는) exception for one event does not prevent processing of others`() = runTest {
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
        fun `handler throws일 때 semaphore은(는) always released even이다`() = runTest {
            val firstHandled = CountDownLatch(1)
            coEvery { eventHandler.handleAppMention(any()) } coAnswers {
                firstHandled.countDown()
                throw RuntimeException("forced error")
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = false)
            )

            val (p1, _, _) = mentionPayload(user = "U1")
            processor.submitEventCallback(p1, "events_api")
            firstHandled.await(5, TimeUnit.SECONDS) shouldBe true
            AsyncTestSupport.settleBackground()  // finally 블록에서 세마포어 해제 대기

            // 세마포어가 해제되지 않았다면 이 두 번째 이벤트는 타임아웃됩니다
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
