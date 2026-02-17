package com.arc.reactor.agent.impl

import com.arc.reactor.memory.TokenEstimator
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

class ConversationMessageTrimmerTest {

    private val tokenEstimator = TokenEstimator { text -> text.length }

    @Test
    fun `should remove assistant tool-call and tool-response as a pair when trimming from front`() {
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
    fun `should keep only the last user message when budget is non-positive`() {
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
    fun `should trim tool history after last user when over budget`() {
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

        val trimmer = ConversationMessageTrimmer(
            maxContextWindowTokens = 10,
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
}
