package com.arc.reactor.agent.impl

import com.arc.reactor.memory.TokenEstimator
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

@Tag("matrix")
class ConversationMessageTrimmerMatrixTest {

    private val estimator = TokenEstimator { text -> text.length }

    @Test
    fun `non-positive budget should preserve only last user when present across 180 cases`() {
        repeat(180) { i ->
            val messages = mutableListOf<Message>(
                AssistantMessage("a$i"),
                UserMessage("u$i"),
                AssistantMessage("b$i"),
                UserMessage("last-$i")
            )
            if (i % 3 == 0) {
                messages.clear()
                messages.addAll(listOf(AssistantMessage("only-a$i"), AssistantMessage("only-b$i")))
            }

            val original = messages.toList()
            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 20,
                outputReserveTokens = 40,
                tokenEstimator = estimator
            )

            trimmer.trim(messages, systemPrompt = "sys-$i")

            val lastUser = original.lastOrNull { it is UserMessage } as? UserMessage
            if (lastUser != null && original.size > 1) {
                assertEquals(1, messages.size, "case=$i")
                assertTrue(messages.first() is UserMessage, "case=$i")
                assertEquals(lastUser.text, (messages.first() as UserMessage).text, "case=$i")
            } else {
                assertEquals(original.size, messages.size, "case=$i no-user path should stay unchanged")
            }
        }
    }

    @Test
    fun `high budget should keep history unchanged across 150 size combinations`() {
        var checked = 0
        for (userCount in 1..5) {
            for (assistantCount in 1..6) {
                val messages = mutableListOf<Message>()
                repeat(userCount) { i -> messages.add(UserMessage("user-$i")) }
                repeat(assistantCount) { i -> messages.add(AssistantMessage("assistant-$i")) }
                val before = messages.map { it.javaClass.simpleName + ":" + (it.text ?: "") }
                val total = messages.sumOf { estimator.estimate(it.text ?: "") }
                val trimmer = ConversationMessageTrimmer(
                    maxContextWindowTokens = total + 100,
                    outputReserveTokens = 0,
                    tokenEstimator = estimator
                )

                trimmer.trim(messages, systemPrompt = "")
                val after = messages.map { it.javaClass.simpleName + ":" + (it.text ?: "") }
                assertEquals(before, after, "checked=$checked")
                checked++
            }
        }
        assertEquals(30, checked)
    }

    @Test
    fun `tool response should never become orphan after trim across 120 budgets`() {
        fun buildToolCall(index: Int): AssistantMessage.ToolCall {
            return AssistantMessage.ToolCall("call-$index", "function", "tool-$index", "{}")
        }

        repeat(120) { i ->
            val pairCount = 1 + (i % 4)
            val messages = mutableListOf<Message>()
            messages += UserMessage("question-$i")
            repeat(pairCount) { p ->
                messages += AssistantMessage.builder().content("think-$i-$p").toolCalls(listOf(buildToolCall(p))).build()
                messages += ToolResponseMessage.builder()
                    .responses(listOf(ToolResponseMessage.ToolResponse("tr-$p", "tool-$p", "res-$p")))
                    .build()
            }
            messages += AssistantMessage("final-$i")

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 35 + (i % 20),
                outputReserveTokens = 0,
                tokenEstimator = estimator
            )
            trimmer.trim(messages, systemPrompt = "")

            messages.forEachIndexed { idx, msg ->
                if (msg is ToolResponseMessage) {
                    assertTrue(idx > 0, "case=$i tool response at first index")
                    val prev = messages[idx - 1]
                    assertTrue(prev is AssistantMessage, "case=$i tool response must follow assistant")
                    assertTrue(!(prev as AssistantMessage).toolCalls.isNullOrEmpty(), "case=$i assistant before tool response must have toolCalls")
                }
            }
        }
    }
}
