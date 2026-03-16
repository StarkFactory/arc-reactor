package com.arc.reactor.agent.impl

import com.arc.reactor.memory.TokenEstimator
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

class ConversationMessageTrimmerTest {

    private val tokenEstimator = TokenEstimator { text -> text.length }

    @Test
    fun `trimming from front일 때 remove assistant tool-call and tool-response as a pair해야 한다`() {
        val toolCall = mockk<AssistantMessage.ToolCall>()
        every { toolCall.name() } returns "tool"
        every { toolCall.arguments() } returns "{}"

        val messages = mutableListOf<Message>(
            AssistantMessage.builder().content("a").toolCalls(listOf(toolCall)).build(),
            ToolResponseMessage.builder()
                .responses(listOf(ToolResponseMessage.ToolResponse("1", "tool", "res")))
                .build(),
            UserMessage("last")
        )

        val trimmer = ConversationMessageTrimmer(
            maxContextWindowTokens = 10,
            outputReserveTokens = 0,
            tokenEstimator = tokenEstimator
        )

        trimmer.trim(messages, systemPrompt = "")

        assertEquals(1, messages.size)
        assertInstanceOf(UserMessage::class.java, messages[0])
        assertEquals("last", (messages[0] as UserMessage).text)
    }

    @Test
    fun `budget is non-positive일 때 keep only the last user message해야 한다`() {
        val messages = mutableListOf<Message>(
            UserMessage("old"),
            AssistantMessage("answer"),
            UserMessage("last")
        )

        val trimmer = ConversationMessageTrimmer(
            maxContextWindowTokens = 5,
            outputReserveTokens = 10,
            tokenEstimator = tokenEstimator
        )

        trimmer.trim(messages, systemPrompt = "sys")

        assertEquals(1, messages.size)
        assertInstanceOf(UserMessage::class.java, messages[0])
        assertEquals("last", (messages[0] as UserMessage).text)
    }

    @Test
    fun `protect leading SystemMessages from trimming해야 한다`() {
        // Simulates hierarchical memory: [Facts, Narrative, old User, old Assistant, recent User]
        val messages = mutableListOf<Message>(
            SystemMessage("Conversation Facts:\n- order: #1234"),
            SystemMessage("Conversation Summary:\nCustomer asked about order"),
            UserMessage("old message"),
            AssistantMessage("old reply"),
            UserMessage("recent question")
        )

        // Budget only fits ~30 chars — must trim but protect SystemMessages
        val trimmer = ConversationMessageTrimmer(
            maxContextWindowTokens = 80,
            outputReserveTokens = 0,
            tokenEstimator = tokenEstimator
        )

        trimmer.trim(messages, systemPrompt = "sys")

        // SystemMessages must survive; old user/assistant은(는) be trimmed해야 합니다
        val systemMessages = messages.filterIsInstance<SystemMessage>()
        assertEquals(2, systemMessages.size) {
            "Both leading SystemMessages (facts + narrative) must be preserved"
        }
        val userMessages = messages.filterIsInstance<UserMessage>()
        assertEquals(1, userMessages.size) { "Recent UserMessage must survive" }
        assertEquals("recent question", userMessages[0].text) { "Must be the recent message" }
    }

    @Test
    fun `budget is extremely tight일 때 keep SystemMessages and last UserMessage해야 한다`() {
        // Even with tiny budget, SystemMessages + last UserMessage은(는) survive해야 합니다
        val messages = mutableListOf<Message>(
            SystemMessage("Facts: order=#1234"),
            SystemMessage("Summary: customer wants refund"),
            UserMessage("old q1"),
            AssistantMessage("old a1"),
            UserMessage("old q2"),
            AssistantMessage("old a2"),
            UserMessage("old q3"),
            AssistantMessage("old a3"),
            UserMessage("recent question")
        )

        // Budget = 1 token — way too small for anything
        val trimmer = ConversationMessageTrimmer(
            maxContextWindowTokens = 1,
            outputReserveTokens = 0,
            tokenEstimator = tokenEstimator
        )

        trimmer.trim(messages, systemPrompt = "")

        // With budget=1, the zero-budget path triggers and keeps only last UserMessage
        // This is acceptable: extreme starvation overrides everything
        val userMessages = messages.filterIsInstance<UserMessage>()
        assertTrue(userMessages.isNotEmpty()) { "At least the last UserMessage must survive" }
        assertEquals("recent question", userMessages.last().text) {
            "The most recent UserMessage must be preserved"
        }
    }

    @Test
    fun `no leading SystemMessages일 때 trim normally해야 한다`() {
        val messages = mutableListOf<Message>(
            UserMessage("old1"),
            AssistantMessage("reply1"),
            UserMessage("old2"),
            AssistantMessage("reply2"),
            UserMessage("recent")
        )

        val trimmer = ConversationMessageTrimmer(
            maxContextWindowTokens = 30,
            outputReserveTokens = 0,
            tokenEstimator = tokenEstimator
        )

        trimmer.trim(messages, systemPrompt = "")

        val lastUser = messages.filterIsInstance<UserMessage>().lastOrNull()
        assertEquals("recent", lastUser?.text) { "Last UserMessage must be preserved" }
    }

    @Test
    fun `over budget일 때 trim tool history after last user해야 한다`() {
        val toolCall = mockk<AssistantMessage.ToolCall>()
        every { toolCall.name() } returns "tool"
        every { toolCall.arguments() } returns "{}"

        val messages = mutableListOf<Message>(
            UserMessage("keep"),
            AssistantMessage.builder().content("a").toolCalls(listOf(toolCall)).build(),
            ToolResponseMessage.builder()
                .responses(listOf(ToolResponseMessage.ToolResponse("1", "tool", "res")))
                .build(),
            AssistantMessage("final")
        )

        // Budget must fit UserMessage("keep") + AssistantMessage("final") including per-message overhead
        val trimmer = ConversationMessageTrimmer(
            maxContextWindowTokens = 50,
            outputReserveTokens = 0,
            tokenEstimator = tokenEstimator
        )

        trimmer.trim(messages, systemPrompt = "")

        assertEquals(2, messages.size)
        assertInstanceOf(UserMessage::class.java, messages[0])
        assertInstanceOf(AssistantMessage::class.java, messages[1])
        assertEquals("keep", (messages[0] as UserMessage).text)
        assertEquals("final", (messages[1] as AssistantMessage).text)
    }

    @Test
    fun `dropping latest tool history 전에 trim leading system messages해야 한다`() {
        val toolCall = mockk<AssistantMessage.ToolCall>()
        every { toolCall.name() } returns "tool"
        every { toolCall.arguments() } returns "{}"

        val messages = mutableListOf<Message>(
            SystemMessage("facts-12345"),
            SystemMessage("summary-6789"),
            UserMessage("keep"),
            AssistantMessage.builder().content("a").toolCalls(listOf(toolCall)).build(),
            ToolResponseMessage.builder()
                .responses(listOf(ToolResponseMessage.ToolResponse("1", "tool", "result-data")))
                .build()
        )

        // Budget must fit UserMessage + AssistantMessage(toolCall) + ToolResponseMessage including per-message overhead
        val trimmer = ConversationMessageTrimmer(
            maxContextWindowTokens = 102,
            outputReserveTokens = 0,
            tokenEstimator = tokenEstimator
        )

        trimmer.trim(messages, systemPrompt = "")

        assertEquals(3, messages.size, "Fresh tool interaction should remain after trimming leading system memory")
        assertInstanceOf(UserMessage::class.java, messages[0], "Last user message must be preserved")
        assertInstanceOf(AssistantMessage::class.java, messages[1], "Tool-call assistant should remain")
        assertInstanceOf(ToolResponseMessage::class.java, messages[2], "Tool response should remain paired")
        assertEquals("keep", (messages[0] as UserMessage).text)
    }

    @Test
    fun `it is the only post-user message over budget일 때 trim trailing assistant해야 한다`() {
        val messages = mutableListOf<Message>(
            UserMessage("keep"),
            AssistantMessage("very-long-assistant-message")
        )

        val trimmer = ConversationMessageTrimmer(
            maxContextWindowTokens = 6,
            outputReserveTokens = 0,
            tokenEstimator = tokenEstimator
        )

        trimmer.trim(messages, systemPrompt = "")

        assertEquals(1, messages.size, "Trailing assistant message should be trimmed when over budget")
        assertInstanceOf(UserMessage::class.java, messages[0], "Last user message must be preserved")
        assertEquals("keep", (messages[0] as UserMessage).text, "Preserved user message should remain unchanged")
    }

    @Test
    fun `delegate token estimation to TokenEstimator on each trim call해야 한다`() {
        val estimatorCalls = linkedMapOf<String, Int>()
        val countingEstimator = TokenEstimator { text ->
            estimatorCalls[text] = (estimatorCalls[text] ?: 0) + 1
            text.length
        }
        val messages = mutableListOf<Message>(
            UserMessage("user-1"),
            AssistantMessage("assistant-1"),
            UserMessage("user-2")
        )
        val trimmer = ConversationMessageTrimmer(
            maxContextWindowTokens = 200,
            outputReserveTokens = 0,
            tokenEstimator = countingEstimator
        )

        trimmer.trim(messages, systemPrompt = "sys")
        trimmer.trim(messages, systemPrompt = "sys")

        // Caching is now the TokenEstimator's responsibility (e.g. DefaultTokenEstimator uses Caffeine).
        // The trimmer delegates every call, so a plain lambda estimator is invoked each time.
        assertEquals(2, estimatorCalls["user-1"], "User message should be estimated once per trim call")
        assertEquals(2, estimatorCalls["assistant-1"], "Assistant message should be estimated once per trim call")
        assertEquals(2, estimatorCalls["user-2"], "Most recent user message should be estimated once per trim call")
        assertEquals(2, estimatorCalls["sys"], "System prompt should be estimated once per trim call")
    }
}
