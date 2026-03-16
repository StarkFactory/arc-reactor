package com.arc.reactor.memory

import com.arc.reactor.memory.model.UserMemory
import com.arc.reactor.support.throwIfCancellation
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
    private val maxRecentTopics: Int = 10,
    private val maxPromptInjectionChars: Int = DEFAULT_MAX_PROMPT_INJECTION_CHARS
) {

    /**
     * Converts the user's stored memory into a structured context string.
     *
     * Example output:
     * ```
     * Facts: team=backend, role=senior engineer
     * Preferences: language=Korean, detail_level=brief
     * ```
     *
     * Returns an empty string if no memory is found or if the memory contains no usable data.
     * Output is truncated to [maxPromptInjectionChars] characters.
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
            e.throwIfCancellation()
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
        val lines = mutableListOf<String>()

        if (memory.facts.isNotEmpty()) {
            val factsStr = memory.facts.entries.joinToString(", ") { "${it.key}=${it.value}" }
            lines.add("Facts: $factsStr")
        }

        if (memory.preferences.isNotEmpty()) {
            val prefsStr = memory.preferences.entries.joinToString(", ") { "${it.key}=${it.value}" }
            lines.add("Preferences: $prefsStr")
        }

        if (lines.isEmpty()) return ""

        val result = lines.joinToString("\n")
        return if (result.length > maxPromptInjectionChars) {
            result.take(maxPromptInjectionChars)
        } else {
            result
        }
    }

    companion object {
        /** Default maximum character length for injected user memory context. */
        const val DEFAULT_MAX_PROMPT_INJECTION_CHARS = 1000
    }
}
