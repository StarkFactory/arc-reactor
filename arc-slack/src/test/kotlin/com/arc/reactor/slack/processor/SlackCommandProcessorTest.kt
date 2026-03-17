package com.arc.reactor.slack.processor

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.service.SlackMessagingService
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
 * [SlackCommandProcessor]의 슬래시 커맨드 처리 테스트.
 *
 * 성공적인 제출, fail-fast 백프레셔, 큐 모드 백프레셔,
 * 드롭 시 알림 의미론, 오류 격리, 세마포어 수명주기 정확성을 검증한다.
 */
class SlackCommandProcessorTest {

    private val commandHandler = mockk<SlackCommandHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)

    private fun buildProcessor(properties: SlackProperties): SlackCommandProcessor =
        SlackCommandProcessor(commandHandler, messagingService, metricsRecorder, properties)

    private fun defaultProperties(
        maxConcurrentRequests: Int = 5,
        failFastOnSaturation: Boolean = true,
        notifyOnDrop: Boolean = false,
        requestTimeoutMs: Long = 5000
    ) = SlackProperties(
        enabled = true,
        maxConcurrentRequests = maxConcurrentRequests,
        failFastOnSaturation = failFastOnSaturation,
        notifyOnDrop = notifyOnDrop,
        requestTimeoutMs = requestTimeoutMs
    )

    private fun buildCommand(
        command: String = "/ask",
        userId: String = "U123",
        channelId: String = "C456",
        responseUrl: String = "https://hooks.slack.com/commands/test"
    ) = SlackSlashCommand(
        command = command,
        text = "some text",
        userId = userId,
        userName = "alice",
        channelId = channelId,
        channelName = "general",
        responseUrl = responseUrl,
        triggerId = "trig-001"
    )

    // =========================================================================
    // 제출 및 반환 값
    // =========================================================================

    @Nested
    inner class Submission {

        @Test
        fun `submit returns true when semaphore은(는) available이다`() {
            val processor = buildProcessor(defaultProperties())
            val result = processor.submit(buildCommand(), "events_api")
            result shouldBe true
        }

        @Test
        fun `saturated in fail-fast mode일 때 submit returns false`() = runTest {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                acquiredLatch.countDown()
                holdLatch.await(5, TimeUnit.SECONDS)
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = true)
            )

            processor.submit(buildCommand(userId = "U1"), "events_api") shouldBe true
            acquiredLatch.await(5, TimeUnit.SECONDS) shouldBe true // 결정적: 퍼밋 획득을 기다립니다

            val rejected = processor.submit(buildCommand(userId = "U2"), "events_api")
            rejected shouldBe false

            holdLatch.countDown()
        }

        @Test
        fun `submit은(는) calls recordInbound on every invocation`() {
            val processor = buildProcessor(defaultProperties())

            processor.submit(buildCommand(), "events_api")

            verify { metricsRecorder.recordInbound(entrypoint = "events_api") }
        }

        @Test
        fun `submit returns true again after semaphore은(는) released이다`() = runTest {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1)
            val releasedLatch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                acquiredLatch.countDown()
                holdLatch.await(5, TimeUnit.SECONDS)
                releasedLatch.countDown()
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = true)
            )

            processor.submit(buildCommand(userId = "U1"), "events_api")
            acquiredLatch.await(5, TimeUnit.SECONDS) shouldBe true // 결정적: 퍼밋 획득을 기다립니다

            holdLatch.countDown() // release
            releasedLatch.await(5, TimeUnit.SECONDS)
            Thread.sleep(200) // Dispatchers.Default에서 세마포어 해제 전파 대기

            val result = processor.submit(buildCommand(userId = "U2"), "events_api")
            result shouldBe true
        }
    }

    // =========================================================================
    // 핸들러 디스패치
    // =========================================================================

    @Nested
    inner class HandlerDispatch {

        @Test
        fun `handleSlashCommand은(는) invoked with the submitted command이다`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers { latch.countDown() }

            val processor = buildProcessor(defaultProperties())
            val command = buildCommand(command = "/ask", userId = "U777")

            processor.submit(command, "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            coVerify {
                commandHandler.handleSlashCommand(match { it.command == "/ask" && it.userId == "U777" })
            }
        }

        @Test
        fun `recordHandler success metric은(는) emitted after successful handling이다`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers { latch.countDown() }

            val processor = buildProcessor(defaultProperties())
            processor.submit(buildCommand(), "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            Thread.sleep(200)  // Dispatchers.Default에서 recordHandler 디스패치 대기
            coVerify {
                metricsRecorder.recordHandler(
                    entrypoint = "events_api",
                    eventType = "/ask",
                    success = true,
                    durationMs = any()
                )
            }
        }

        @Test
        fun `handler throws일 때 recordHandler failure metric은(는) emitted이다`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                latch.countDown()
                throw RuntimeException("handler error")
            }

            val processor = buildProcessor(defaultProperties())
            processor.submit(buildCommand(), "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            Thread.sleep(200)  // Dispatchers.Default에서 recordHandler 디스패치 대기
            coVerify {
                metricsRecorder.recordHandler(
                    entrypoint = "events_api",
                    eventType = "/ask",
                    success = false,
                    durationMs = any()
                )
            }
        }
    }

    // =========================================================================
    // 백프레셔 — fail-fast 모드
    // =========================================================================

    @Nested
    inner class BackpressureFailFastMode {

        @Test
        fun `saturated일 때 recordDropped with queue_overflow은(는) emitted이다`() = runTest {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                acquiredLatch.countDown()
                holdLatch.await(5, TimeUnit.SECONDS)
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = true)
            )
            processor.submit(buildCommand(userId = "U1"), "events_api")
            acquiredLatch.await(5, TimeUnit.SECONDS) shouldBe true // 결정적: 퍼밋 획득을 기다립니다

            processor.submit(buildCommand(userId = "U2"), "events_api")
            holdLatch.countDown()

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "events_api",
                    reason = "queue_overflow",
                    eventType = "/ask"
                )
            }
        }

        @Test
        fun `enabled일 때 notifyOnDrop sends busy message via response_url`() {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1) // signals when first handler holds semaphore
            runBlocking {
                coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                    acquiredLatch.countDown()
                    holdLatch.await(5, TimeUnit.SECONDS)
                }
                coEvery { messagingService.sendResponseUrl(any(), any(), any()) } returns true

                val processor = buildProcessor(
                    defaultProperties(
                        maxConcurrentRequests = 1,
                        failFastOnSaturation = false,
                        notifyOnDrop = true,
                        requestTimeoutMs = 200
                    )
                )
                processor.submit(
                    buildCommand(userId = "U1", responseUrl = "https://hooks.slack.com/commands/u1"),
                    "events_api"
                )
                acquiredLatch.await(5, TimeUnit.SECONDS)  // 첫 번째 핸들러가 세마포어를 보유할 때까지 대기

                processor.submit(
                    buildCommand(userId = "U2", responseUrl = "https://hooks.slack.com/commands/u2"),
                    "events_api"
                )
            }
            // 타임아웃 + 알림 시간이 Dispatchers.Default에서 실행될 수 있도록 대기
            Thread.sleep(1000)
            holdLatch.countDown()

            coVerify(timeout = 3000) {
                messagingService.sendResponseUrl(
                    responseUrl = any(),
                    text = match { it.contains("busy", ignoreCase = true) },
                    any()
                )
            }
        }

        @Test
        fun `disabled일 때 notifyOnDrop은(는) suppressed이다`() = runTest {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
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
            processor.submit(buildCommand(userId = "U1"), "events_api")
            acquiredLatch.await(5, TimeUnit.SECONDS) shouldBe true // 결정적: 퍼밋 획득을 기다립니다
            processor.submit(buildCommand(userId = "U2"), "events_api")

            holdLatch.countDown()
            Thread.sleep(500) // let processing settle on Dispatchers.Default

            coVerify(exactly = 0) { messagingService.sendResponseUrl(any(), any(), any()) }
        }
    }

    // =========================================================================
    // 백프레셔 — 타임아웃이 있는 큐 모드
    // =========================================================================

    @Nested
    inner class BackpressureQueueModeTimeout {

        @Test
        fun `capacity allows일 때 commands wait in queue and all complete`() = runTest {
            val processedCount = AtomicInteger(0)
            val latch = CountDownLatch(3)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                delay(50)
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
            repeat(3) { i -> processor.submit(buildCommand(userId = "U$i"), "events_api") }

            latch.await(10, TimeUnit.SECONDS) shouldBe true
            processedCount.get() shouldBe 3
        }

        @Test
        fun `timeout elapses일 때 recordDropped with queue_timeout은(는) emitted이다`() {
            val holdLatch = CountDownLatch(1)
            val acquiredLatch = CountDownLatch(1)
            runBlocking {
                coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
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
                processor.submit(buildCommand(userId = "U1"), "events_api")
                acquiredLatch.await(5, TimeUnit.SECONDS) // first handler holds semaphore

                processor.submit(buildCommand(userId = "U2"), "events_api")
            }
            Thread.sleep(800)  // 200ms 타임아웃 + 처리 마진 대기
            holdLatch.countDown()

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "events_api",
                    reason = "queue_timeout",
                    eventType = "/ask"
                )
            }
        }
    }

    // =========================================================================
    // 오류 격리
    // =========================================================================

    @Nested
    inner class ErrorIsolation {

        @Test
        fun `handler은(는) exception does not prevent subsequent commands from being processed`() = runTest {
            val successCount = AtomicInteger(0)
            val latch = CountDownLatch(3)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                val cmd = firstArg<SlackSlashCommand>()
                if (cmd.userId == "U1") throw RuntimeException("Forced failure")
                successCount.incrementAndGet()
                latch.countDown()
            }

            val processor = buildProcessor(defaultProperties(maxConcurrentRequests = 5))
            listOf("U1", "U2", "U3", "U4").forEach { user ->
                processor.submit(buildCommand(userId = user), "events_api")
            }

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            successCount.get() shouldBe 3
        }

        @Test
        fun `semaphore은(는) released after handler throws allowing next command to proceed이다`() = runTest {
            val firstHandled = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                firstHandled.countDown()
                throw RuntimeException("forced")
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = false)
            )

            processor.submit(buildCommand(userId = "U1"), "events_api")
            firstHandled.await(5, TimeUnit.SECONDS) shouldBe true
            Thread.sleep(200)  // Dispatchers.Default의 finally 블록에서 세마포어 해제 대기

            // 다음 커맨드 성공을 위해 모킹 리셋
            val secondLatch = CountDownLatch(1)
            val secondSuccess = AtomicInteger(0)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                secondSuccess.incrementAndGet()
                secondLatch.countDown()
            }

            val result = processor.submit(buildCommand(userId = "U2"), "events_api")
            result shouldBe true

            secondLatch.await(5, TimeUnit.SECONDS) shouldBe true
            secondSuccess.get() shouldBe 1
        }

        @Test
        fun `multiple concurrent commands은(는) independent and all metrics are recorded이다`() = runTest {
            val totalCommands = 4
            val latch = CountDownLatch(totalCommands)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers { latch.countDown() }

            val processor = buildProcessor(defaultProperties(maxConcurrentRequests = totalCommands))
            repeat(totalCommands) { i -> processor.submit(buildCommand(userId = "U$i"), "events_api") }

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            Thread.sleep(200)  // Dispatchers.Default에서 recordHandler 디스패치 대기
            coVerify(exactly = totalCommands) {
                metricsRecorder.recordHandler(any(), any(), success = true, durationMs = any())
            }
        }
    }

    // =========================================================================
    // 동시성 — 퍼밋 카운팅
    // =========================================================================

    @Nested
    inner class ConcurrencyInvariants {

        @Test
        fun `peak은(는) concurrent command processing never exceeds maxConcurrentRequests`() = runTest {
            val maxAllowed = 2
            val totalCommands = 8
            val currentConcurrent = AtomicInteger(0)
            val peakConcurrent = AtomicInteger(0)
            val latch = CountDownLatch(totalCommands)

            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                val current = currentConcurrent.incrementAndGet()
                peakConcurrent.updateAndGet { prev -> maxOf(prev, current) }
                delay(100)
                currentConcurrent.decrementAndGet()
                latch.countDown()
            }

            val processor = buildProcessor(
                defaultProperties(
                    maxConcurrentRequests = maxAllowed,
                    failFastOnSaturation = false,
                    requestTimeoutMs = 10000
                )
            )
            repeat(totalCommands) { i -> processor.submit(buildCommand(userId = "U$i"), "events_api") }

            latch.await(15, TimeUnit.SECONDS) shouldBe true
            peakConcurrent.get() shouldBe maxAllowed
        }
    }
}
