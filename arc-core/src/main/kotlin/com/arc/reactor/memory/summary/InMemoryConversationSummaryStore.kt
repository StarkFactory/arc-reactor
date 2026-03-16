package com.arc.reactor.memory.summary

import java.util.concurrent.ConcurrentHashMap

/**
 * ConcurrentHashMap 기반의 인메모리 [ConversationSummaryStore].
 *
 * 개발 및 테스트에 적합하다. 서버 재시작 시 데이터가 유실된다.
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
