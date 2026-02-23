package com.arc.reactor.memory.summary

/**
 * Persistence layer for conversation summaries.
 *
 * @see InMemoryConversationSummaryStore for default implementation
 * @see JdbcConversationSummaryStore for JDBC-backed implementation
 */
interface ConversationSummaryStore {

    /**
     * Retrieve the summary for a session.
     *
     * @param sessionId Session identifier
     * @return Existing summary or null
     */
    fun get(sessionId: String): ConversationSummary?

    /**
     * Save or update a summary for a session.
     *
     * If a summary already exists, it is replaced (upsert semantics).
     *
     * @param summary Summary to persist
     */
    fun save(summary: ConversationSummary)

    /**
     * Delete the summary for a session.
     *
     * @param sessionId Session identifier
     */
    fun delete(sessionId: String)
}
