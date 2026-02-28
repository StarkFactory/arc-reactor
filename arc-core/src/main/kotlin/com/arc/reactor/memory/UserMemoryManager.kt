package com.arc.reactor.memory

import com.arc.reactor.memory.model.UserMemory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Service layer over [UserMemoryStore].
 *
 * Provides higher-level operations such as converting stored memory into a
 * natural-language context string that can be injected into the system prompt.
 */
class UserMemoryManager(
    private val store: UserMemoryStore,
    private val maxRecentTopics: Int = 10
) {

    /**
     * Converts the user's stored memory into a single natural-language context string.
     *
     * Example output:
     * ```
     * User context: team=backend, role=senior engineer | recent topics: Spring AI, MCP integration
     * ```
     *
     * Returns an empty string if no memory is found or if the memory contains no usable data.
     */
    suspend fun getContextPrompt(userId: String): String {
        val memory = store.get(userId) ?: return ""
        return buildContextPrompt(memory)
    }

    /**
     * Appends [topic] to the user's recent topics, respecting [maxRecentTopics].
     */
    suspend fun recordTopic(userId: String, topic: String) {
        try {
            store.addRecentTopic(userId, topic, maxRecentTopics)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to record topic for user $userId" }
        }
    }

    /** Delegates directly to the underlying store. */
    suspend fun get(userId: String): UserMemory? = store.get(userId)

    /** Delegates directly to the underlying store. */
    suspend fun save(userId: String, memory: UserMemory) = store.save(userId, memory)

    /** Delegates directly to the underlying store. */
    suspend fun delete(userId: String) = store.delete(userId)

    /** Delegates directly to the underlying store. */
    suspend fun updateFact(userId: String, key: String, value: String) =
        store.updateFact(userId, key, value)

    /** Delegates directly to the underlying store. */
    suspend fun updatePreference(userId: String, key: String, value: String) =
        store.updatePreference(userId, key, value)

    private fun buildContextPrompt(memory: UserMemory): String {
        val parts = mutableListOf<String>()

        val factPart = memory.facts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        if (factPart.isNotBlank()) parts.add(factPart)

        val prefPart = memory.preferences.entries.joinToString(", ") { "${it.key}=${it.value}" }
        if (prefPart.isNotBlank()) parts.add(prefPart)

        val topicPart = if (memory.recentTopics.isNotEmpty()) {
            "recent topics: ${memory.recentTopics.joinToString(", ")}"
        } else null

        if (parts.isEmpty() && topicPart == null) return ""

        val contextBody = buildString {
            if (parts.isNotEmpty()) append(parts.joinToString(", "))
            if (topicPart != null) {
                if (parts.isNotEmpty()) append(" | ")
                append(topicPart)
            }
        }
        return "User context: $contextBody"
    }
}
