package com.arc.reactor.memory.summary

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ConversationSummaryStore] backed by ConcurrentHashMap.
 *
 * Suitable for development and testing. Not persistent across restarts.
 */
class InMemoryConversationSummaryStore : ConversationSummaryStore {

    private val store = ConcurrentHashMap<String, ConversationSummary>()

    override fun get(sessionId: String): ConversationSummary? = store[sessionId]

    override fun save(summary: ConversationSummary) {
        store[summary.sessionId] = summary
    }

    override fun delete(sessionId: String) {
        store.remove(sessionId)
    }
}
