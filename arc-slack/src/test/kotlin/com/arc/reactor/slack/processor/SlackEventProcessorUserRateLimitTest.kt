package com.arc.reactor.slack.processor

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.resilience.SlackUserRateLimiter
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.support.AsyncTestSupport
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

/**
 * [SlackEventProcessor]의 사용자별 레이트 리미터 통합 테스트.
 *
 * userRateLimiter가 주입되었을 때의 드롭, 메트릭, 알림 동작을 검증한다.
 */
class SlackEventProcessorUserRateLimitTest {

    private val objectMapper = jacksonObjectMapper()
    private val eventHandler = mockk<SlackEventHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)

    private fun defaultProperties() = SlackProperties(
        enabled = true,
        maxConcurrentRequests = 5,
        failFastOnSaturation = true,
        userRateLimitEnabled = true,
        userRateLimitMaxPerMinute = 2
    )

    private fun buildProcessor(
        properties: SlackProperties = defaultProperties(),
        rateLimiter: SlackUserRateLimiter? = SlackUserRateLimiter(maxRequestsPerMinute = 2)
    ) = SlackEventProcessor(
        eventHandler, messagingService, metricsRecorder, properties,
        userRateLimiter = rateLimiter
    )

    private fun mentionPayload(user: String = "U123", ts: String = "1000.001") =
        objectMapper.readTree(
            """{"type":"event_callback","event":{"type":"app_mention","user":"$user","channel":"C456","text":"hello","ts":"$ts"}}"""
        )

    @Nested
    inner class RateLimitedDrop {

        @Test
        fun `rate limit 초과 시 이벤트를 드롭한다`() = runTest {
            val processor = buildProcessor()

            // 첫 2개는 허용
            processor.submitEventCallback(mentionPayload(ts = "1.0"), "events_api")
            processor.submitEventCallback(mentionPayload(ts = "2.0"), "events_api")

            coVerify(timeout = 2000, exactly = 2) { eventHandler.handleAppMention(any()) }

            // 3번째는 rate limit 초과 → 드롭
            processor.submitEventCallback(mentionPayload(ts = "3.0"), "events_api")
            AsyncTestSupport.settleBackground()

            coVerify(exactly = 2) { eventHandler.handleAppMention(any()) }
        }

        @Test
        fun `rate limit 시 recordDropped 메트릭을 기록한다`() = runTest {
            val processor = buildProcessor()

            // 2개 허용 후 3번째 드롭
            processor.submitEventCallback(mentionPayload(ts = "1.0"), "events_api")
            processor.submitEventCallback(mentionPayload(ts = "2.0"), "events_api")
            processor.submitEventCallback(mentionPayload(ts = "3.0"), "events_api")

            verify(timeout = 2000) {
                metricsRecorder.recordDropped(
                    entrypoint = "events_api",
                    reason = "user_rate_limited",
                    eventType = "app_mention"
                )
            }
        }

        @Test
        fun `rate limit 시 사용자에게 알림 메시지를 전송한다`() = runTest {
            coEvery { messagingService.sendMessage(any(), any(), any()) } returns
                com.arc.reactor.slack.model.SlackApiResult(ok = true)

            val processor = buildProcessor()

            // 2개 허용 후 3번째 드롭
            processor.submitEventCallback(mentionPayload(ts = "1.0"), "events_api")
            processor.submitEventCallback(mentionPayload(ts = "2.0"), "events_api")
            processor.submitEventCallback(mentionPayload(ts = "3.0"), "events_api")

            coVerify(timeout = 3000) {
                messagingService.sendMessage(
                    channelId = "C456",
                    text = match { it.contains(":no_entry:") },
                    threadTs = any()
                )
            }
        }
    }

    @Nested
    inner class RateLimiterDisabled {

        @Test
        fun `rateLimiter가 null이면 모든 이벤트를 처리한다`() = runTest {
            val processor = buildProcessor(rateLimiter = null)

            repeat(5) { i ->
                processor.submitEventCallback(mentionPayload(ts = "$i.0"), "events_api")
            }

            coVerify(timeout = 3000, exactly = 5) { eventHandler.handleAppMention(any()) }
        }
    }

    @Nested
    inner class DifferentUsersNotAffected {

        @Test
        fun `다른 사용자의 rate limit은 영향을 받지 않는다`() = runTest {
            val processor = buildProcessor()

            // U1: 2개 허용
            processor.submitEventCallback(
                objectMapper.readTree(
                    """{"type":"event_callback","event":{"type":"app_mention","user":"U1","channel":"C1","text":"hi","ts":"1.0"}}"""
                ), "events_api"
            )
            processor.submitEventCallback(
                objectMapper.readTree(
                    """{"type":"event_callback","event":{"type":"app_mention","user":"U1","channel":"C1","text":"hi","ts":"2.0"}}"""
                ), "events_api"
            )

            // U2: 별도 카운터이므로 허용
            processor.submitEventCallback(
                objectMapper.readTree(
                    """{"type":"event_callback","event":{"type":"app_mention","user":"U2","channel":"C1","text":"hi","ts":"3.0"}}"""
                ), "events_api"
            )

            coVerify(timeout = 3000, exactly = 3) { eventHandler.handleAppMention(any()) }
        }
    }
}
