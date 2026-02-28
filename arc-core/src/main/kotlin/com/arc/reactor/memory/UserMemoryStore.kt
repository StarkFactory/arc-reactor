package com.arc.reactor.memory

import com.arc.reactor.memory.model.UserMemory

/**
 * Persistent store for per-user long-term memory.
 *
 * Implementations must be thread-safe and support concurrent access from multiple coroutines.
 * The default implementation is [impl.InMemoryUserMemoryStore].
 * When a DataSource is available, [impl.JdbcUserMemoryStore] is auto-configured with @Primary.
 */
interface UserMemoryStore {

    /** Returns the stored [UserMemory] for the given user, or null if none exists. */
    suspend fun get(userId: String): UserMemory?

    /** Persists (creates or replaces) the [UserMemory] for the given user. */
    suspend fun save(userId: String, memory: UserMemory)

    /** Removes all stored memory for the given user. Idempotent. */
    suspend fun delete(userId: String)

    /**
     * Upserts a single fact entry for the user.
     *
     * If no memory record exists yet, one is created with only this fact.
     */
    suspend fun updateFact(userId: String, key: String, value: String)

    /**
     * Upserts a single preference entry for the user.
     *
     * If no memory record exists yet, one is created with only this preference.
     */
    suspend fun updatePreference(userId: String, key: String, value: String)

    /**
     * Appends [topic] to the user's recent topics list.
     *
     * The list is bounded by [maxTopics]; the oldest entry is dropped when the limit is reached.
     */
    suspend fun addRecentTopic(userId: String, topic: String, maxTopics: Int = 10)
}
