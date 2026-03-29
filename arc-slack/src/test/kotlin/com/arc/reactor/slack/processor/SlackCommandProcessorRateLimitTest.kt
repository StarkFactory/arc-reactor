package com.arc.reactor.slack.processor

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.resilience.SlackUserRateLimiter
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.support.AsyncTestSupport
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SlackCommandProcessor]의 사용자별 레이트 리미터 통합 테스트.
 *
 * userRateLimiter 주입 시 드롭, 메트릭, response_url 알림 동작과
 * 서로 다른 사용자 간 독립적인 레이트 리밋 동작을 검증한다.
 */
class SlackCommandProcessorRateLimitTest {

    private val commandHandler = mockk<SlackCommandHandler>(relaxed = true)
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
    ) = SlackCommandProcessor(
        commandHandler, messagingService, metricsRecorder, properties,
        userRateLimiter = rateLimiter
    )

    private fun buildCommand(
        userId: String = "U123",
        command: String = "/ask",
        responseUrl: String = "https://hooks.slack.com/commands/test"
    ) = SlackSlashCommand(
        command = command,
        text = "some text",
        userId = userId,
        userName = "alice",
        channelId = "C456",
        channelName = "general",
        responseUrl = responseUrl,
        triggerId = "trig-001"
    )

    // =========================================================================
    // 레이트 리밋 초과 시 드롭
    // =========================================================================

    @Nested
    inner class RateLimitedDrop {

        @Test
        fun `레이트 리밋 초과 시 submit이 false를 반환한다`() {
            val processor = buildProcessor()

            // 2개 허용
            processor.submit(buildCommand(userId = "U1"), "events_api") shouldBe true
            processor.submit(buildCommand(userId = "U1"), "events_api") shouldBe true

            // 3번째는 레이트 리밋 초과 → false
            val result = processor.submit(buildCommand(userId = "U1"), "events_api")
            result shouldBe false
        }

        @Test
        fun `레이트 리밋 초과 시 recordDropped user_rate_limited 메트릭을 기록한다`() {
            val processor = buildProcessor()

            // 2개 허용
            processor.submit(buildCommand(userId = "U1"), "events_api")
            processor.submit(buildCommand(userId = "U1"), "events_api")

            // 3번째 드롭
            processor.submit(buildCommand(userId = "U1"), "events_api")

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "events_api",
                    reason = "user_rate_limited",
                    eventType = "/ask"
                )
            }
        }

        @Test
        fun `레이트 리밋 초과 시 response_url로 알림 메시지를 전송한다`() = runTest {
            coEvery { messagingService.sendResponseUrl(any(), any(), any()) } returns true

            val processor = buildProcessor()

            // 2개 허용
            processor.submit(buildCommand(userId = "U1", responseUrl = "https://hooks.slack.com/u1"), "events_api")
            processor.submit(buildCommand(userId = "U1", responseUrl = "https://hooks.slack.com/u1"), "events_api")

            // 3번째 → 레이트 리밋 알림
            processor.submit(buildCommand(userId = "U1", responseUrl = "https://hooks.slack.com/u1"), "events_api")

            coVerify(timeout = 3000) {
                messagingService.sendResponseUrl(
                    responseUrl = "https://hooks.slack.com/u1",
                    text = match { it.contains("Too many requests", ignoreCase = true) },
                    any()
                )
            }
        }

        @Test
        fun `레이트 리밋 초과 시 핸들러를 호출하지 않는다`() = runTest {
            val processor = buildProcessor()

            // 2개 허용, 핸들러 호출 대기
            val latch = CountDownLatch(2)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers { latch.countDown() }

            processor.submit(buildCommand(userId = "U1"), "events_api")
            processor.submit(buildCommand(userId = "U1"), "events_api")
            latch.await(5, TimeUnit.SECONDS) shouldBe true

            // 3번째 드롭 후 핸들러 호출 횟수 확인
            processor.submit(buildCommand(userId = "U1"), "events_api")
            AsyncTestSupport.settleBackground()

            coVerify(exactly = 2) { commandHandler.handleSlashCommand(any()) }
        }
    }

    // =========================================================================
    // 서로 다른 사용자 독립성
    // =========================================================================

    @Nested
    inner class DifferentUsersNotAffected {

        @Test
        fun `서로 다른 사용자의 레이트 리밋은 독립적으로 동작한다`() = runTest {
            val processor = buildProcessor()
            val latch = CountDownLatch(3)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers { latch.countDown() }

            // U1: 2개 허용
            processor.submit(buildCommand(userId = "U1"), "events_api")
            processor.submit(buildCommand(userId = "U1"), "events_api")

            // U2: 별도 카운터이므로 허용
            processor.submit(buildCommand(userId = "U2"), "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            latch.count shouldBe 0
        }

        @Test
        fun `U1이 레이트 리밋 초과여도 U2는 정상 처리된다`() = runTest {
            val processedCount = AtomicInteger(0)
            val processor = buildProcessor()
            val u2Latch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                processedCount.incrementAndGet()
                val cmd = firstArg<SlackSlashCommand>()
                if (cmd.userId == "U2") u2Latch.countDown()
            }

            // U1 레이트 리밋 초과
            processor.submit(buildCommand(userId = "U1"), "events_api")
            processor.submit(buildCommand(userId = "U1"), "events_api")
            processor.submit(buildCommand(userId = "U1"), "events_api") // 드롭

            // U2는 여전히 허용
            val accepted = processor.submit(buildCommand(userId = "U2"), "events_api")
            accepted shouldBe true

            u2Latch.await(5, TimeUnit.SECONDS) shouldBe true
        }
    }

    // =========================================================================
    // 레이트 리미터 비활성화
    // =========================================================================

    @Nested
    inner class RateLimiterDisabled {

        @Test
        fun `rateLimiter가 null이면 모든 명령을 처리한다`() = runTest {
            val processor = buildProcessor(rateLimiter = null)
            val latch = CountDownLatch(5)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers { latch.countDown() }

            repeat(5) { i ->
                processor.submit(buildCommand(userId = "U1", command = "/ask"), "events_api")
            }

            latch.await(5, TimeUnit.SECONDS) shouldBe true
        }

        @Test
        fun `rateLimiter가 null이면 user_rate_limited 메트릭이 기록되지 않는다`() {
            val processor = buildProcessor(rateLimiter = null)

            repeat(5) { processor.submit(buildCommand(userId = "U1"), "events_api") }
            AsyncTestSupport.settleBackground()

            verify(exactly = 0) {
                metricsRecorder.recordDropped(any(), reason = "user_rate_limited", any())
            }
        }
    }

    // =========================================================================
    // 레이트 리밋 + recordInbound 상호작용
    // =========================================================================

    @Nested
    inner class RecordInboundAlwaysCalled {

        @Test
        fun `레이트 리밋 드롭 시에도 recordInbound는 항상 호출된다`() {
            val processor = buildProcessor()

            // 3번 모두 호출 (2개 허용 + 1개 드롭)
            processor.submit(buildCommand(userId = "U1"), "events_api")
            processor.submit(buildCommand(userId = "U1"), "events_api")
            processor.submit(buildCommand(userId = "U1"), "events_api")

            verify(exactly = 3) {
                metricsRecorder.recordInbound(entrypoint = "events_api")
            }
        }
    }
}
