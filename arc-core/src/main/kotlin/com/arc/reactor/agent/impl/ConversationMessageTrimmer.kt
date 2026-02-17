package com.arc.reactor.agent.impl

import com.arc.reactor.memory.TokenEstimator
import mu.KotlinLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

private val logger = KotlinLogging.logger {}

/**
 * Trims conversation history to fit within context budget while preserving
 * assistant tool-call and tool-response message pair integrity.
 */
class ConversationMessageTrimmer(
    private val maxContextWindowTokens: Int,
    private val outputReserveTokens: Int,
    private val tokenEstimator: TokenEstimator
) {

    fun trim(messages: MutableList<Message>, systemPrompt: String) {
        val systemTokens = tokenEstimator.estimate(systemPrompt)
        val budget = maxContextWindowTokens - systemTokens - outputReserveTokens

        if (budget <= 0) {
            logger.warn {
                "Context budget is non-positive ($budget). " +
                    "system=$systemTokens, outputReserve=$outputReserveTokens, max=$maxContextWindowTokens"
            }
            val lastUserMsgIndex = messages.indexOfLast { it is UserMessage }
            if (lastUserMsgIndex >= 0 && messages.size > 1) {
                val userMsg = messages[lastUserMsgIndex]
                messages.clear()
                messages.add(userMsg)
            }
            return
        }

        var totalTokens = messages.sumOf { estimateMessageTokens(it) }
        totalTokens = trimOldHistory(messages, totalTokens, budget)
        trimToolHistory(messages, totalTokens, budget)
    }

    /** Phase 1: Remove oldest messages from the front, preserving the last UserMessage. */
    private fun trimOldHistory(messages: MutableList<Message>, currentTokens: Int, budget: Int): Int {
        var totalTokens = currentTokens
        while (totalTokens > budget && messages.size > 1) {
            val protectedIdx = messages.indexOfLast { it is UserMessage }.coerceAtLeast(0)
            if (protectedIdx <= 0) break

            val removeCount = calculateRemoveGroupSize(messages)
            if (removeCount <= 0 || removeCount > protectedIdx) break

            var removedTokens = 0
            repeat(removeCount) {
                if (messages.size > 1) {
                    removedTokens += estimateMessageTokens(messages.removeFirst())
                }
            }
            totalTokens -= removedTokens
            logger.debug { "Trimmed $removeCount messages (old history). Remaining tokens: $totalTokens/$budget" }
        }
        return totalTokens
    }

    /** Phase 2: Remove tool interaction pairs after the last UserMessage when still over budget. */
    private fun trimToolHistory(messages: MutableList<Message>, currentTokens: Int, budget: Int) {
        var totalTokens = currentTokens
        while (totalTokens > budget && messages.size > 1) {
            val protectedIdx = messages.indexOfLast { it is UserMessage }.coerceAtLeast(0)
            val removeStartIdx = protectedIdx + 1
            if (removeStartIdx >= messages.size - 1) break

            val subList = messages.subList(removeStartIdx, messages.size)
            val removeCount = calculateRemoveGroupSize(subList)
            if (removeCount <= 0 || removeStartIdx + removeCount > messages.size) break

            var removedTokens = 0
            repeat(removeCount) {
                if (removeStartIdx < messages.size) {
                    removedTokens += estimateMessageTokens(messages.removeAt(removeStartIdx))
                }
            }
            totalTokens -= removedTokens
            logger.debug { "Trimmed $removeCount messages (tool history). Remaining tokens: $totalTokens/$budget" }
        }
    }

    /**
     * Calculate how many messages from the front should be removed as a group.
     *
     * If the first message is an AssistantMessage with tool calls, the following
     * ToolResponseMessage must also be removed to maintain valid message ordering.
     */
    private fun calculateRemoveGroupSize(messages: List<Message>): Int {
        if (messages.isEmpty()) return 0
        val first = messages[0]

        // AssistantMessage with tool calls -> must also remove the paired ToolResponseMessage
        if (first is AssistantMessage && !first.toolCalls.isNullOrEmpty()) {
            // Find the ToolResponseMessage that follows
            return if (messages.size > 1 && messages[1] is ToolResponseMessage) 2 else 1
        }

        // ToolResponseMessage without preceding AssistantMessage (orphaned) -> remove it
        if (first is ToolResponseMessage) return 1

        // Regular UserMessage or AssistantMessage -> remove single
        return 1
    }

    private fun estimateMessageTokens(message: Message): Int {
        return when (message) {
            is UserMessage -> tokenEstimator.estimate(message.text)
            is AssistantMessage -> {
                val textTokens = tokenEstimator.estimate(message.text ?: "")
                val toolCallTokens = message.toolCalls.sumOf {
                    tokenEstimator.estimate(it.name() + it.arguments())
                }
                textTokens + toolCallTokens
            }
            is SystemMessage -> tokenEstimator.estimate(message.text)
            is ToolResponseMessage -> message.responses.sumOf { tokenEstimator.estimate(it.responseData()) }
            else -> tokenEstimator.estimate(message.text ?: "")
        }
    }
}
