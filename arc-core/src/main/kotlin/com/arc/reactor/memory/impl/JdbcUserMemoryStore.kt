package com.arc.reactor.memory.impl

import com.arc.reactor.memory.UserMemoryStore
import com.arc.reactor.memory.model.UserMemory
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC-backed implementation of [UserMemoryStore].
 *
 * Persists user memory to a relational database using the `user_memories` table.
 * Facts and preferences are serialized as JSON text columns for flexibility.
 * Recent topics are stored as a newline-separated list.
 *
 * ## Table DDL (PostgreSQL / H2 compatible)
 * ```sql
 * CREATE TABLE IF NOT EXISTS user_memories (
 *     user_id       VARCHAR(255) PRIMARY KEY,
 *     facts         TEXT         NOT NULL DEFAULT '{}',
 *     preferences   TEXT         NOT NULL DEFAULT '{}',
 *     recent_topics TEXT         NOT NULL DEFAULT '',
 *     updated_at    TIMESTAMP    NOT NULL
 * );
 * ```
 *
 * Auto-created on first write when [autoCreateTable] is true (default).
 */
class JdbcUserMemoryStore(
    private val jdbcTemplate: JdbcTemplate,
    private val tableName: String = "user_memories",
    private val autoCreateTable: Boolean = true
) : UserMemoryStore {

    private val objectMapper = jacksonObjectMapper()
    private var tableInitialized = false

    init {
        if (autoCreateTable) {
            initTable()
        }
    }

    override suspend fun get(userId: String): UserMemory? {
        return try {
            val results = jdbcTemplate.query(
                "SELECT user_id, facts, preferences, recent_topics, updated_at FROM $tableName WHERE user_id = ?",
                { rs: ResultSet, _: Int -> rs.toUserMemory() },
                userId
            )
            results.firstOrNull()
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to load user memory for $userId" }
            null
        }
    }

    override suspend fun save(userId: String, memory: UserMemory) {
        val factsJson = objectMapper.writeValueAsString(memory.facts)
        val prefsJson = objectMapper.writeValueAsString(memory.preferences)
        val topicsText = memory.recentTopics.joinToString("\n")
        val now = Timestamp.from(Instant.now())

        jdbcTemplate.update(
            """
            INSERT INTO $tableName (user_id, facts, preferences, recent_topics, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE
            SET facts = EXCLUDED.facts,
                preferences = EXCLUDED.preferences,
                recent_topics = EXCLUDED.recent_topics,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            userId, factsJson, prefsJson, topicsText, now
        )
    }

    override suspend fun delete(userId: String) {
        jdbcTemplate.update("DELETE FROM $tableName WHERE user_id = ?", userId)
    }

    override suspend fun updateFact(userId: String, key: String, value: String) {
        val existing = get(userId) ?: UserMemory(userId = userId)
        save(userId, existing.copy(facts = existing.facts + (key to value), updatedAt = Instant.now()))
    }

    override suspend fun updatePreference(userId: String, key: String, value: String) {
        val existing = get(userId) ?: UserMemory(userId = userId)
        save(userId, existing.copy(preferences = existing.preferences + (key to value), updatedAt = Instant.now()))
    }

    override suspend fun addRecentTopic(userId: String, topic: String, maxTopics: Int) {
        val existing = get(userId) ?: UserMemory(userId = userId)
        val updated = (existing.recentTopics + topic).takeLast(maxTopics)
        save(userId, existing.copy(recentTopics = updated, updatedAt = Instant.now()))
    }

    private fun ResultSet.toUserMemory(): UserMemory {
        val factsJson = getString("facts").orEmpty().ifBlank { "{}" }
        val prefsJson = getString("preferences").orEmpty().ifBlank { "{}" }
        val topicsText = getString("recent_topics").orEmpty()
        val topics = if (topicsText.isBlank()) emptyList() else topicsText.split("\n").filter { it.isNotBlank() }

        return UserMemory(
            userId = getString("user_id"),
            facts = objectMapper.readValue(factsJson),
            preferences = objectMapper.readValue(prefsJson),
            recentTopics = topics,
            updatedAt = getTimestamp("updated_at")?.toInstant() ?: Instant.now()
        )
    }

    private fun initTable() {
        if (tableInitialized) return
        try {
            jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS $tableName (
                    user_id       VARCHAR(255) PRIMARY KEY,
                    facts         TEXT         NOT NULL DEFAULT '{}',
                    preferences   TEXT         NOT NULL DEFAULT '{}',
                    recent_topics TEXT         NOT NULL DEFAULT '',
                    updated_at    TIMESTAMP    NOT NULL
                )
                """.trimIndent()
            )
            tableInitialized = true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to auto-create $tableName table. Please create it manually." }
        }
    }
}
