package com.arc.reactor.memory

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.memory.summary.ConversationSummary
import com.arc.reactor.memory.summary.ConversationSummaryService
import com.arc.reactor.memory.summary.ConversationSummaryStore
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.beans.factory.DisposableBean
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Manages the conversation history lifecycle.
 *
 * - Loads conversation history from MemoryStore
 * - Saves conversation history after agent execution
 * - Converts messages between Arc Reactor and Spring AI formats
 * - Optionally applies hierarchical memory (facts + narrative + recent window)
 */
interface ConversationManager {

    /**
     * Loads the command's conversation history as a list of Spring AI messages.
     * If conversationHistory is directly provided, it takes priority;
     * otherwise, retrieves from MemoryStore using the sessionId.
     *
     * When hierarchical memory is enabled and the conversation exceeds
     * the trigger threshold, returns [Facts SystemMessage] + [Narrative SystemMessage]
     * + [recent N messages] instead of a simple takeLast.
     */
    suspend fun loadHistory(command: AgentCommand): List<Message>

    /**
     * Saves a successful agent execution result to the MemoryStore.
     * Failed results are not saved.
     *
     * When hierarchical memory is enabled, triggers async summarization
     * if the conversation exceeds the trigger threshold.
     */
    suspend fun saveHistory(command: AgentCommand, result: AgentResult)

    /**
     * Saves streaming results to the MemoryStore.
     */
    suspend fun saveStreamingHistory(command: AgentCommand, content: String)

    /**
     * Cancels any active async summarization for the given session.
     * Called during session deletion to prevent orphan summaries.
     */
    fun cancelActiveSummarization(sessionId: String) {
        // Default no-op for implementations without async summarization
    }
}

/**
 * Default implementation based on MemoryStore with optional hierarchical memory.
 *
 * When [summaryStore] and [summaryService] are both provided and summary is
 * enabled in [properties], old messages are compressed into structured facts
 * and a narrative summary while recent messages are preserved verbatim.
 */
class DefaultConversationManager(
    private val memoryStore: MemoryStore?,
    private val properties: AgentProperties,
    private val summaryStore: ConversationSummaryStore? = null,
    private val summaryService: ConversationSummaryService? = null
) : ConversationManager, DisposableBean {

    private val summaryProps get() = properties.memory.summary

    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSummarizations = ConcurrentHashMap<String, Job>()

    override suspend fun loadHistory(command: AgentCommand): List<Message> {
        if (command.conversationHistory.isNotEmpty()) {
            return command.conversationHistory.map { toSpringAiMessage(it) }
        }

        val sessionId = command.metadata["sessionId"]?.toString() ?: return emptyList()
        val memory = memoryStore?.get(sessionId) ?: return emptyList()
        val allMessages = memory.getHistory()

        if (!isSummaryEnabled() || allMessages.size <= summaryProps.triggerMessageCount) {
            return allMessages.takeLast(properties.llm.maxConversationTurns * 2)
                .map { toSpringAiMessage(it) }
        }

        return try {
            buildHierarchicalHistory(sessionId, allMessages)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Hierarchical memory failed for session $sessionId, falling back to takeLast" }
            allMessages.takeLast(properties.llm.maxConversationTurns * 2)
                .map { toSpringAiMessage(it) }
        }
    }

    override suspend fun saveHistory(command: AgentCommand, result: AgentResult) {
        if (!result.success) return
        saveMessages(command.metadata, command.userId, command.userPrompt, result.content)
        triggerAsyncSummarization(command.metadata)
    }

    override suspend fun saveStreamingHistory(command: AgentCommand, content: String) {
        saveMessages(command.metadata, command.userId, command.userPrompt, content.ifEmpty { null })
        triggerAsyncSummarization(command.metadata)
    }

    private fun calculateSplitIndex(totalSize: Int): Int =
        (totalSize - summaryProps.recentMessageCount).coerceAtLeast(0)

    private suspend fun buildHierarchicalHistory(
        sessionId: String,
        allMessages: List<com.arc.reactor.agent.model.Message>
    ): List<Message> {
        val splitIndex = calculateSplitIndex(allMessages.size)

        // Nothing to summarize — all messages fit in the recent window
        if (splitIndex == 0) {
            return allMessages.map { toSpringAiMessage(it) }
        }

        val messagesToSummarize = allMessages.subList(0, splitIndex)
        val recentMessages = allMessages.subList(splitIndex, allMessages.size)

        val existingSummary = summaryStore?.get(sessionId)

        val summary = if (existingSummary != null && existingSummary.summarizedUpToIndex >= splitIndex) {
            existingSummary
        } else {
            summarizeAndStore(sessionId, messagesToSummarize, existingSummary)
        }

        val result = mutableListOf<Message>()

        if (summary.facts.isNotEmpty()) {
            val factsText = summary.facts.joinToString("\n") { "- ${it.key}: ${it.value}" }
            result.add(SystemMessage("Conversation Facts:\n$factsText"))
        }

        if (summary.narrative.isNotBlank()) {
            result.add(SystemMessage("Conversation Summary:\n${summary.narrative}"))
        }

        // Empty summary — fall back to takeLast to avoid silent context loss
        if (result.isEmpty()) {
            logger.warn { "Summary produced no content for session $sessionId, falling back to takeLast" }
            return allMessages.takeLast(properties.llm.maxConversationTurns * 2)
                .map { toSpringAiMessage(it) }
        }

        for (msg in recentMessages) {
            result.add(toSpringAiMessage(msg))
        }

        return result
    }

    private suspend fun summarizeAndStore(
        sessionId: String,
        messages: List<com.arc.reactor.agent.model.Message>,
        existingSummary: ConversationSummary?
    ): ConversationSummary {
        val service = summaryService
            ?: throw IllegalStateException("SummaryService is required for hierarchical memory")

        val summaryResult = service.summarize(messages, existingSummary?.facts.orEmpty())
        val summary = ConversationSummary(
            sessionId = sessionId,
            narrative = summaryResult.narrative,
            facts = summaryResult.facts,
            summarizedUpToIndex = messages.size,
            createdAt = existingSummary?.createdAt ?: Instant.now(),
            updatedAt = Instant.now()
        )
        summaryStore?.save(summary)
        return summary
    }

    override fun cancelActiveSummarization(sessionId: String) {
        activeSummarizations.remove(sessionId)?.cancel()
    }

    private fun triggerAsyncSummarization(metadata: Map<String, Any>) {
        if (!isSummaryEnabled()) return
        val sessionId = metadata["sessionId"]?.toString() ?: return
        val memory = memoryStore?.get(sessionId) ?: return
        val allMessages = memory.getHistory()

        if (allMessages.size <= summaryProps.triggerMessageCount) return

        val splitIndex = calculateSplitIndex(allMessages.size)
        if (splitIndex == 0) return

        activeSummarizations[sessionId]?.cancel()

        val job = asyncScope.launch {
            try {
                val messagesToSummarize = allMessages.subList(0, splitIndex)
                val existingSummary = summaryStore?.get(sessionId)
                summarizeAndStore(sessionId, messagesToSummarize, existingSummary)
                logger.debug { "Async summarization completed for session $sessionId" }
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.debug(e) { "Async summarization failed for session $sessionId (will retry on next load)" }
            }
        }
        activeSummarizations[sessionId] = job
        job.invokeOnCompletion { activeSummarizations.remove(sessionId, job) }
    }

    private fun isSummaryEnabled(): Boolean =
        summaryProps.enabled && summaryStore != null && summaryService != null

    override fun destroy() {
        asyncScope.cancel()
    }

    private fun saveMessages(
        metadata: Map<String, Any>,
        userId: String?,
        userPrompt: String,
        assistantContent: String?
    ) {
        val sessionId = metadata["sessionId"]?.toString() ?: return
        if (memoryStore == null) return
        val resolvedUserId = userId ?: "anonymous"

        try {
            memoryStore.addMessage(
                sessionId = sessionId, role = "user",
                content = userPrompt, userId = resolvedUserId
            )
            if (assistantContent != null) {
                memoryStore.addMessage(
                    sessionId = sessionId, role = "assistant",
                    content = assistantContent, userId = resolvedUserId
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save conversation history for session $sessionId" }
        }
    }

    companion object {
        fun toSpringAiMessage(msg: com.arc.reactor.agent.model.Message): Message {
            return when (msg.role) {
                MessageRole.USER -> MediaConverter.buildUserMessage(msg.content, msg.media)
                MessageRole.ASSISTANT -> AssistantMessage(msg.content)
                MessageRole.SYSTEM -> SystemMessage(msg.content)
                MessageRole.TOOL -> ToolResponseMessage.builder()
                    .responses(listOf(ToolResponseMessage.ToolResponse("", "tool", msg.content)))
                    .build()
            }
        }
    }
}
