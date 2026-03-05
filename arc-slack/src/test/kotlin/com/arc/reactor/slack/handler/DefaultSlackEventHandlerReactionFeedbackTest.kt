package com.arc.reactor.slack.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.feedback.Feedback
import com.arc.reactor.feedback.FeedbackRating
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.feedback.InMemoryFeedbackStore
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.memory.UserMemoryStore
import com.arc.reactor.memory.model.UserMemory
import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.slack.session.SlackBotResponseTracker
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultSlackEventHandlerReactionFeedbackTest {

    private val agentExecutor = mockk<AgentExecutor>()
    private val messagingService = mockk<SlackMessagingService>()
    private val feedbackStore = InMemoryFeedbackStore()
    private val botResponseTracker = SlackBotResponseTracker()

    private fun buildHandler(
        feedbackStore: FeedbackStore? = this.feedbackStore,
        tracker: SlackBotResponseTracker? = this.botResponseTracker,
        userMemoryManager: UserMemoryManager? = null
    ) = DefaultSlackEventHandler(
        agentExecutor = agentExecutor,
        messagingService = messagingService,
        feedbackStore = feedbackStore,
        botResponseTracker = tracker,
        userMemoryManager = userMemoryManager
    )

    @Nested
    inner class ReactionFeedback {

        @Test
        fun `thumbsup reaction saves THUMBS_UP feedback`() = runTest {
            val handler = buildHandler()

            handler.handleReaction(
                userId = "U1",
                channelId = "C1",
                messageTs = "1.0",
                reaction = "thumbsup",
                sessionId = "slack-C1-1.0",
                userPrompt = "What is Kotlin?"
            )

            feedbackStore.count() shouldBe 1
            val fb = feedbackStore.list().first()
            fb.rating shouldBe FeedbackRating.THUMBS_UP
            fb.sessionId shouldBe "slack-C1-1.0"
            fb.userId shouldBe "U1"
            fb.query shouldBe "What is Kotlin?"
        }

        @Test
        fun `thumbsdown reaction saves THUMBS_DOWN feedback`() = runTest {
            val handler = buildHandler()

            handler.handleReaction(
                userId = "U2",
                channelId = "C1",
                messageTs = "2.0",
                reaction = "-1",
                sessionId = "s2",
                userPrompt = "help"
            )

            feedbackStore.count() shouldBe 1
            feedbackStore.list().first().rating shouldBe FeedbackRating.THUMBS_DOWN
        }

        @Test
        fun `unknown reaction emoji does not save feedback`() = runTest {
            val handler = buildHandler()

            handler.handleReaction(
                userId = "U1", channelId = "C1", messageTs = "1.0",
                reaction = "heart", sessionId = "s1", userPrompt = "hi"
            )

            feedbackStore.count() shouldBe 0
        }

        @Test
        fun `no-op when feedbackStore is null`() = runTest {
            val handler = buildHandler(feedbackStore = null)

            handler.handleReaction(
                userId = "U1", channelId = "C1", messageTs = "1.0",
                reaction = "thumbsup", sessionId = "s1", userPrompt = "hi"
            )
            // no exception, no store to check
        }
    }

    @Nested
    inner class BotResponseTracking {

        @Test
        fun `tracks bot response after successful send`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns AgentResult(
                success = true, content = "Kotlin is a JVM language."
            )
            coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(
                ok = true, ts = "1.002", channel = "C1"
            )

            val handler = buildHandler()
            handler.handleAppMention(
                SlackEventCommand("app_mention", "U1", "C1", "<@BOT> What is Kotlin?", "1.001", null)
            )

            val tracked = botResponseTracker.lookup("C1", "1.002")
            tracked?.sessionId shouldBe "slack-C1-1.001"
            tracked?.userPrompt shouldBe "What is Kotlin?"
        }
    }

    @Nested
    inner class UserMemoryInjection {

        @Test
        fun `injects user context into system prompt when memory exists`() = runTest {
            val memoryStore = mockk<UserMemoryStore>()
            coEvery { memoryStore.get("U1") } returns UserMemory(
                userId = "U1",
                facts = mapOf("team" to "backend", "role" to "senior engineer"),
                preferences = mapOf("language" to "Korean")
            )
            val memoryManager = UserMemoryManager(memoryStore)

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult(
                success = true, content = "답변"
            )
            coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(ok = true)

            val handler = buildHandler(userMemoryManager = memoryManager)
            handler.handleAppMention(
                SlackEventCommand("app_mention", "U1", "C1", "<@BOT> help", "1.0", null)
            )

            commandSlot.captured.systemPrompt shouldContain "team=backend"
            commandSlot.captured.systemPrompt shouldContain "role=senior engineer"
            commandSlot.captured.systemPrompt shouldContain "language=Korean"
        }

        @Test
        fun `does not inject user context when memory is null`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult(
                success = true, content = "ok"
            )
            coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(ok = true)

            val handler = buildHandler(userMemoryManager = null)
            handler.handleAppMention(
                SlackEventCommand("app_mention", "U1", "C1", "<@BOT> hi", "1.0", null)
            )

            // Should not contain "User context:" since no memory manager
            commandSlot.captured.systemPrompt.contains("User context:") shouldBe false
        }
    }
}
