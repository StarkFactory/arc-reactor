package com.arc.reactor.memory

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MessageRole
import mu.KotlinLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

private val logger = KotlinLogging.logger {}

/**
 * Manages the conversation history lifecycle.
 *
 * - Loads conversation history from MemoryStore
 * - Saves conversation history after agent execution
 * - Converts messages between Arc Reactor and Spring AI formats
 */
interface ConversationManager {

    /**
     * Loads the command's conversation history as a list of Spring AI messages.
     * If conversationHistory is directly provided, it takes priority;
     * otherwise, retrieves from MemoryStore using the sessionId.
     */
    fun loadHistory(command: AgentCommand): List<Message>

    /**
     * Saves a successful agent execution result to the MemoryStore.
     * Failed results are not saved.
     */
    fun saveHistory(command: AgentCommand, result: AgentResult)

    /**
     * Saves streaming results to the MemoryStore.
     */
    fun saveStreamingHistory(command: AgentCommand, content: String)
}

/**
 * Default implementation based on MemoryStore.
 */
class DefaultConversationManager(
    private val memoryStore: MemoryStore?,
    private val properties: AgentProperties
) : ConversationManager {

    override fun loadHistory(command: AgentCommand): List<Message> {
        if (command.conversationHistory.isNotEmpty()) {
            return command.conversationHistory.map { toSpringAiMessage(it) }
        }

        val sessionId = command.metadata["sessionId"]?.toString() ?: return emptyList()
        val memory = memoryStore?.get(sessionId) ?: return emptyList()

        return memory.getHistory().takeLast(properties.llm.maxConversationTurns * 2)
            .map { toSpringAiMessage(it) }
    }

    override fun saveHistory(command: AgentCommand, result: AgentResult) {
        if (!result.success) return
        saveMessages(command.metadata, command.userId, command.userPrompt, result.content)
    }

    override fun saveStreamingHistory(command: AgentCommand, content: String) {
        saveMessages(command.metadata, command.userId, command.userPrompt, content.ifEmpty { null })
    }

    private fun saveMessages(metadata: Map<String, Any>, userId: String?, userPrompt: String, assistantContent: String?) {
        val sessionId = metadata["sessionId"]?.toString() ?: return
        if (memoryStore == null) return
        val resolvedUserId = userId ?: "anonymous"

        try {
            memoryStore.addMessage(sessionId = sessionId, role = "user", content = userPrompt, userId = resolvedUserId)
            if (assistantContent != null) {
                memoryStore.addMessage(sessionId = sessionId, role = "assistant", content = assistantContent, userId = resolvedUserId)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save conversation history for session $sessionId" }
        }
    }

    companion object {
        fun toSpringAiMessage(msg: com.arc.reactor.agent.model.Message): Message {
            return when (msg.role) {
                MessageRole.USER -> UserMessage(msg.content)
                MessageRole.ASSISTANT -> AssistantMessage(msg.content)
                MessageRole.SYSTEM -> SystemMessage(msg.content)
                MessageRole.TOOL -> ToolResponseMessage.builder()
                    .responses(listOf(ToolResponseMessage.ToolResponse("", "tool", msg.content)))
                    .build()
            }
        }
    }
}
