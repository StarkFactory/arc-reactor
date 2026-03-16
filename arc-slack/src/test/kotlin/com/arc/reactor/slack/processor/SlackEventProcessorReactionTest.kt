package com.arc.reactor.slack.processor

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.slack.session.SlackBotResponseTracker
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

class SlackEventProcessorReactionTest {

    private val objectMapper = jacksonObjectMapper()
    private val eventHandler = mockk<SlackEventHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)

    private val defaultProperties = SlackProperties(
        enabled = true,
        reactionFeedbackEnabled = true,
        maxConcurrentRequests = 5,
        failFastOnSaturation = true
    )

    private fun buildTracker(): SlackBotResponseTracker {
        val tracker = SlackBotResponseTracker()
        tracker.track("C100", "2000.002", "slack-C100-2000.001", "How do I deploy?")
        return tracker
    }

    private fun buildProcessor(
        tracker: SlackBotResponseTracker? = buildTracker()
    ) = SlackEventProcessor(
        eventHandler, messagingService, metricsRecorder, defaultProperties,
        botResponseTracker = tracker
    )

    private fun reactionPayload(
        channel: String = "C100",
        ts: String = "2000.002",
        reaction: String = "thumbsup",
        user: String = "U1"
    ) = objectMapper.readTree(
        """{"type":"event_callback","event":{"type":"reaction_added","user":"$user",""" +
            """"reaction":"$reaction","item":{"type":"message","channel":"$channel","ts":"$ts"}}}"""
    )

    @Nested
    inner class ReactionRouting {

        @Test
        fun `thumbsup reaction to handleReaction for tracked bot message를 라우팅한다`() = runTest {
            val latch = CountDownLatch(1)
            coEvery {
                eventHandler.handleReaction(any(), any(), any(), any(), any(), any())
            } coAnswers { latch.countDown() }

            val processor = buildProcessor()
            processor.submitEventCallback(reactionPayload(), "events_api")

            latch.await(3, TimeUnit.SECONDS) shouldBe true
            coVerify {
                eventHandler.handleReaction(
                    userId = "U1",
                    channelId = "C100",
                    messageTs = "2000.002",
                    reaction = "thumbsup",
                    sessionId = "slack-C100-2000.001",
                    userPrompt = "How do I deploy?"
                )
            }
        }

        @Test
        fun `thumbsdown reaction to handleReaction를 라우팅한다`() = runTest {
            val latch = CountDownLatch(1)
            coEvery {
                eventHandler.handleReaction(any(), any(), any(), any(), any(), any())
            } coAnswers { latch.countDown() }

            val processor = buildProcessor()
            processor.submitEventCallback(
                reactionPayload(reaction = "-1"), "events_api"
            )

            latch.await(3, TimeUnit.SECONDS) shouldBe true
            coVerify {
                eventHandler.handleReaction(
                    userId = "U1",
                    channelId = "C100",
                    messageTs = "2000.002",
                    reaction = "-1",
                    sessionId = "slack-C100-2000.001",
                    userPrompt = "How do I deploy?"
                )
            }
        }

        @Test
        fun `reaction on untracked message를 무시한다`() = runTest {
            val processor = buildProcessor()
            processor.submitEventCallback(
                reactionPayload(ts = "9999.999"), "events_api"
            )
            Thread.sleep(500)

            coVerify(exactly = 0) {
                eventHandler.handleReaction(any(), any(), any(), any(), any(), any())
            }
        }

        @Test
        fun `ignores reaction when bot response tracker은(는) null이다`() = runTest {
            val processor = buildProcessor(tracker = null)
            processor.submitEventCallback(reactionPayload(), "events_api")
            Thread.sleep(500)

            coVerify(exactly = 0) {
                eventHandler.handleReaction(any(), any(), any(), any(), any(), any())
            }
        }

        @Test
        fun `reaction on non-message item type를 무시한다`() = runTest {
            val payload = objectMapper.readTree(
                """{"type":"event_callback","event":{"type":"reaction_added","user":"U1",""" +
                    """"reaction":"thumbsup","item":{"type":"file","channel":"C100","ts":"2000.002"}}}"""
            )
            val processor = buildProcessor()
            processor.submitEventCallback(payload, "events_api")
            Thread.sleep(500)

            coVerify(exactly = 0) {
                eventHandler.handleReaction(any(), any(), any(), any(), any(), any())
            }
        }
    }

    @Nested
    inner class ReactionMetrics {

        @Test
        fun `reaction_feedback success metric를 기록한다`() = runTest {
            val latch = CountDownLatch(1)
            coEvery {
                eventHandler.handleReaction(any(), any(), any(), any(), any(), any())
            } coAnswers { latch.countDown() }

            val processor = buildProcessor()
            processor.submitEventCallback(reactionPayload(), "events_api")

            latch.await(3, TimeUnit.SECONDS) shouldBe true
            Thread.sleep(200)
            verify {
                metricsRecorder.recordHandler(
                    entrypoint = "events_api",
                    eventType = "reaction_feedback",
                    success = true,
                    durationMs = any()
                )
            }
        }
    }
}
