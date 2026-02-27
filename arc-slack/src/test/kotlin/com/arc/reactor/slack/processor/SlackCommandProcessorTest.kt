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
 * Unit tests for [SlackCommandProcessor].
 *
 * Covers successful submission, fail-fast backpressure, queue-mode backpressure,
 * notification-on-drop semantics, error isolation, and semaphore lifecycle correctness.
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
    // Submission and return value
    // =========================================================================

    @Nested
    inner class Submission {

        @Test
        fun `submit returns true when semaphore is available`() {
            val processor = buildProcessor(defaultProperties())
            val result = processor.submit(buildCommand(), "events_api")
            result shouldBe true
        }

        @Test
        fun `submit returns false when saturated in fail-fast mode`() = runTest {
            val holdLatch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                holdLatch.await(5, TimeUnit.SECONDS)
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = true)
            )

            processor.submit(buildCommand(userId = "U1"), "events_api") shouldBe true
            delay(100) // let first command acquire the permit

            val rejected = processor.submit(buildCommand(userId = "U2"), "events_api")
            rejected shouldBe false

            holdLatch.countDown()
        }

        @Test
        fun `submit calls recordInbound on every invocation`() {
            val processor = buildProcessor(defaultProperties())

            processor.submit(buildCommand(), "events_api")

            verify { metricsRecorder.recordInbound(entrypoint = "events_api") }
        }

        @Test
        fun `submit returns true again after semaphore is released`() = runTest {
            val holdLatch = CountDownLatch(1)
            val releasedLatch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                holdLatch.await(5, TimeUnit.SECONDS)
                releasedLatch.countDown()
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = true)
            )

            processor.submit(buildCommand(userId = "U1"), "events_api")
            delay(100) // let first acquire permit

            holdLatch.countDown() // release
            releasedLatch.await(5, TimeUnit.SECONDS)
            delay(100) // let semaphore release propagate

            val result = processor.submit(buildCommand(userId = "U2"), "events_api")
            result shouldBe true
        }
    }

    // =========================================================================
    // Handler dispatch
    // =========================================================================

    @Nested
    inner class HandlerDispatch {

        @Test
        fun `handleSlashCommand is invoked with the submitted command`() = runTest {
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
        fun `recordHandler success metric is emitted after successful handling`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers { latch.countDown() }

            val processor = buildProcessor(defaultProperties())
            processor.submit(buildCommand(), "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            delay(100)
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
        fun `recordHandler failure metric is emitted when handler throws`() = runTest {
            val latch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                latch.countDown()
                throw RuntimeException("handler error")
            }

            val processor = buildProcessor(defaultProperties())
            processor.submit(buildCommand(), "events_api")

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            delay(100)
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
    // Backpressure — fail-fast mode
    // =========================================================================

    @Nested
    inner class BackpressureFailFastMode {

        @Test
        fun `recordDropped with queue_overflow is emitted when saturated`() = runTest {
            val holdLatch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
                holdLatch.await(5, TimeUnit.SECONDS)
            }

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = true)
            )
            processor.submit(buildCommand(userId = "U1"), "events_api")
            delay(100)

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
        fun `notifyOnDrop sends busy message via response_url when enabled`() {
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
                acquiredLatch.await(5, TimeUnit.SECONDS) // wait for first handler to hold semaphore

                processor.submit(
                    buildCommand(userId = "U2", responseUrl = "https://hooks.slack.com/commands/u2"),
                    "events_api"
                )
            }
            // Give timeout + notify time to fire on Dispatchers.Default
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
        fun `notifyOnDrop is suppressed when disabled`() = runTest {
            val holdLatch = CountDownLatch(1)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers {
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
            delay(100)
            processor.submit(buildCommand(userId = "U2"), "events_api")

            holdLatch.countDown()
            delay(300)

            coVerify(exactly = 0) { messagingService.sendResponseUrl(any(), any(), any()) }
        }
    }

    // =========================================================================
    // Backpressure — queue mode with timeout
    // =========================================================================

    @Nested
    inner class BackpressureQueueModeTimeout {

        @Test
        fun `commands wait in queue and all complete when capacity allows`() = runTest {
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
        fun `recordDropped with queue_timeout is emitted when timeout elapses`() {
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
            Thread.sleep(800) // wait for 200ms timeout + processing margin
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
    // Error isolation
    // =========================================================================

    @Nested
    inner class ErrorIsolation {

        @Test
        fun `handler exception does not prevent subsequent commands from being processed`() = runTest {
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
        fun `semaphore is released after handler throws allowing next command to proceed`() = runTest {
            coEvery { commandHandler.handleSlashCommand(any()) } throws RuntimeException("forced")

            val processor = buildProcessor(
                defaultProperties(maxConcurrentRequests = 1, failFastOnSaturation = false)
            )

            processor.submit(buildCommand(userId = "U1"), "events_api")
            delay(300) // let handler run and fail

            // Reset mock to succeed for next command
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
        fun `multiple concurrent commands are independent and all metrics are recorded`() = runTest {
            val totalCommands = 4
            val latch = CountDownLatch(totalCommands)
            coEvery { commandHandler.handleSlashCommand(any()) } coAnswers { latch.countDown() }

            val processor = buildProcessor(defaultProperties(maxConcurrentRequests = totalCommands))
            repeat(totalCommands) { i -> processor.submit(buildCommand(userId = "U$i"), "events_api") }

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            delay(100)
            coVerify(exactly = totalCommands) {
                metricsRecorder.recordHandler(any(), any(), success = true, durationMs = any())
            }
        }
    }

    // =========================================================================
    // Concurrency — permit counting
    // =========================================================================

    @Nested
    inner class ConcurrencyInvariants {

        @Test
        fun `peak concurrent command processing never exceeds maxConcurrentRequests`() = runTest {
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
