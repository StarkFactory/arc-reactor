package com.arc.reactor.memory.model

import java.time.Instant

/**
 * Long-term memory record for a single user.
 *
 * Persists across conversation sessions to provide personalized context.
 * Facts and preferences are stored as simple key-value pairs.
 * Recent topics (up to [maxRecentTopics]) act as a sliding window of conversation subjects.
 */
data class UserMemory(
    val userId: String,

    /** Factual attributes — e.g. "team" -> "backend", "role" -> "senior engineer" */
    val facts: Map<String, String> = emptyMap(),

    /** User preferences — e.g. "language" -> "Korean", "detail_level" -> "brief" */
    val preferences: Map<String, String> = emptyMap(),

    /** Recent conversation topics (newest last, bounded to maxRecentTopics). */
    val recentTopics: List<String> = emptyList(),

    /** Timestamp of the last update. */
    val updatedAt: Instant = Instant.now()
)
