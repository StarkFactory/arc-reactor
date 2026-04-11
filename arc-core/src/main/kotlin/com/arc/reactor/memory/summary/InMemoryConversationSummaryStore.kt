package com.arc.reactor.memory.summary

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

/**
 * Caffeine bounded cache 기반의 인메모리 [ConversationSummaryStore].
 *
 * 개발 및 테스트에 적합하다. 서버 재시작 시 데이터가 유실된다.
 *
 * R312 fix: ConcurrentHashMap → Caffeine bounded cache. 기존 구현은 `save()`가
 * 반복되면 무제한 성장 가능성이 있었다. 이제 [maxSummaries] 상한(기본 10,000)을
 * 넘으면 W-TinyLFU 정책으로 evict.
 */
class InMemoryConversationSummaryStore(
    maxSummaries: Long = DEFAULT_MAX_SUMMARIES
) : ConversationSummaryStore {

    private val store: Cache<String, ConversationSummary> = Caffeine.newBuilder()
        .maximumSize(maxSummaries)
        .build()

    override fun get(sessionId: String): ConversationSummary? = store.getIfPresent(sessionId)

    override fun save(summary: ConversationSummary) {
        store.put(summary.sessionId, summary)
    }

    override fun delete(sessionId: String) {
        store.invalidate(sessionId)
    }

    /** 테스트 전용: Caffeine 지연 maintenance를 강제 실행한다. */
    internal fun forceCleanUp() {
        store.cleanUp()
    }

    companion object {
        /** 기본 대화 요약 상한. 초과 시 Caffeine W-TinyLFU 정책으로 evict. */
        const val DEFAULT_MAX_SUMMARIES: Long = 10_000L
    }
}
