package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC-based MemoryStore for persistent conversation storage.
 *
 * Stores conversation history in a relational database (PostgreSQL, H2, etc.).
 * Requires the `conversation_messages` table — see Flyway migration V1.
 *
 * ## Features
 * - Persistent across server restarts
 * - Per-session max message limit (FIFO eviction)
 * - Session cleanup by TTL
 * - Thread-safe via database transactions
 *
 * ## Usage
 * When `spring-boot-starter-jdbc` and a DataSource are on the classpath,
 * this bean is auto-configured by [ArcReactorAutoConfiguration].
 */
class JdbcMemoryStore(
    private val jdbcTemplate: JdbcTemplate,
    private val maxMessagesPerSession: Int = 100,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : MemoryStore {

    override fun get(sessionId: String): ConversationMemory? {
        val messages = loadMessages(sessionId)
        return if (messages.isEmpty()) null else JdbcConversationMemory(sessionId, messages)
    }

    override fun getOrCreate(sessionId: String): ConversationMemory {
        val messages = loadMessages(sessionId)
        return JdbcConversationMemory(sessionId, messages)
    }

    override fun remove(sessionId: String) {
        jdbcTemplate.update("DELETE FROM conversation_messages WHERE session_id = ?", sessionId)
    }

    override fun clear() {
        jdbcTemplate.update("DELETE FROM conversation_messages")
    }

    override fun addMessage(sessionId: String, role: String, content: String) {
        jdbcTemplate.update(
            "INSERT INTO conversation_messages (session_id, role, content, timestamp) VALUES (?, ?, ?, ?)",
            sessionId, role, content, Instant.now().toEpochMilli()
        )

        // Enforce max messages per session (FIFO eviction)
        evictOldMessages(sessionId)
    }

    /**
     * Delete sessions that have been inactive for longer than the specified TTL.
     *
     * @param ttlMs Time-to-live in milliseconds
     * @return Number of sessions cleaned up
     */
    fun cleanupExpiredSessions(ttlMs: Long): Int {
        val cutoff = Instant.now().toEpochMilli() - ttlMs
        // Find sessions whose latest message is older than the cutoff
        val expiredSessions = jdbcTemplate.queryForList(
            """
            SELECT session_id FROM conversation_messages
            GROUP BY session_id
            HAVING MAX(timestamp) < ?
            """.trimIndent(),
            String::class.java,
            cutoff
        )

        if (expiredSessions.isNotEmpty()) {
            for (sessionId in expiredSessions) {
                remove(sessionId)
            }
            logger.info { "Cleaned up ${expiredSessions.size} expired sessions" }
        }

        return expiredSessions.size
    }

    private fun loadMessages(sessionId: String): List<Message> {
        return jdbcTemplate.query(
            "SELECT role, content, timestamp FROM conversation_messages WHERE session_id = ? ORDER BY id ASC",
            { rs: ResultSet, _: Int ->
                Message(
                    role = parseRole(rs.getString("role")),
                    content = rs.getString("content"),
                    timestamp = Instant.ofEpochMilli(rs.getLong("timestamp"))
                )
            },
            sessionId
        )
    }

    private fun evictOldMessages(sessionId: String) {
        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM conversation_messages WHERE session_id = ?",
            Int::class.java,
            sessionId
        ) ?: 0

        if (count > maxMessagesPerSession) {
            val excess = count - maxMessagesPerSession
            jdbcTemplate.update(
                """
                DELETE FROM conversation_messages WHERE id IN (
                    SELECT id FROM conversation_messages
                    WHERE session_id = ?
                    ORDER BY id ASC
                    LIMIT ?
                )
                """.trimIndent(),
                sessionId, excess
            )
        }
    }

    private fun parseRole(role: String): MessageRole {
        return when (role.lowercase()) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            "system" -> MessageRole.SYSTEM
            "tool" -> MessageRole.TOOL
            else -> MessageRole.USER
        }
    }

    /**
     * Read-only view of a JDBC-backed conversation for a session.
     */
    private inner class JdbcConversationMemory(
        private val sessionId: String,
        private val cachedMessages: List<Message>
    ) : ConversationMemory {

        override fun add(message: Message) {
            addMessage(sessionId, message.role.name.lowercase(), message.content)
        }

        override fun getHistory(): List<Message> = cachedMessages

        override fun clear() {
            remove(sessionId)
        }

        override fun getHistoryWithinTokenLimit(maxTokens: Int): List<Message> {
            var totalTokens = 0
            val result = mutableListOf<Message>()

            for (message in cachedMessages.reversed()) {
                val tokens = tokenEstimator.estimate(message.content)
                if (totalTokens + tokens > maxTokens) break
                result.add(0, message)
                totalTokens += tokens
            }

            return result
        }
    }
}
