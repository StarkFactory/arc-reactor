package com.arc.reactor.memory.impl

import com.arc.reactor.memory.UserMemoryStore
import com.arc.reactor.memory.model.UserMemory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [UserMemoryStore].
 *
 * Uses a [ConcurrentHashMap] for thread-safe access without external dependencies.
 * Data is lost on server restart — use [JdbcUserMemoryStore] for persistence.
 *
 * This is the default implementation auto-configured when no DataSource is available.
 */
class InMemoryUserMemoryStore : UserMemoryStore {

    private val store = ConcurrentHashMap<String, UserMemory>()

    override suspend fun get(userId: String): UserMemory? = store[userId]

    override suspend fun save(userId: String, memory: UserMemory) {
        store[userId] = memory
    }

    override suspend fun delete(userId: String) {
        store.remove(userId)
    }

    override suspend fun updateFact(userId: String, key: String, value: String) {
        val existing = store[userId] ?: UserMemory(userId = userId)
        store[userId] = existing.copy(
            facts = existing.facts + (key to value),
            updatedAt = Instant.now()
        )
    }

    override suspend fun updatePreference(userId: String, key: String, value: String) {
        val existing = store[userId] ?: UserMemory(userId = userId)
        store[userId] = existing.copy(
            preferences = existing.preferences + (key to value),
            updatedAt = Instant.now()
        )
    }

    override suspend fun addRecentTopic(userId: String, topic: String, maxTopics: Int) {
        val existing = store[userId] ?: UserMemory(userId = userId)
        val updated = (existing.recentTopics + topic).takeLast(maxTopics)
        store[userId] = existing.copy(recentTopics = updated, updatedAt = Instant.now())
    }
}
