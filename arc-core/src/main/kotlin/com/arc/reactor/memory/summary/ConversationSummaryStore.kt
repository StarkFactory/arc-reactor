package com.arc.reactor.memory.summary

/**
 * 대화 요약의 영속 계층.
 *
 * @see InMemoryConversationSummaryStore 기본 인메모리 구현체
 * @see JdbcConversationSummaryStore JDBC 기반 구현체
 */
interface ConversationSummaryStore {

    /**
     * 세션의 요약을 조회한다.
     *
     * @param sessionId 세션 식별자
     * @return 기존 요약 또는 null
     */
    fun get(sessionId: String): ConversationSummary?

    /**
     * 세션의 요약을 저장 또는 갱신한다.
     * 이미 요약이 존재하면 교체된다 (upsert 시맨틱).
     *
     * @param summary 저장할 요약
     */
    fun save(summary: ConversationSummary)

    /**
     * 세션의 요약을 삭제한다.
     *
     * @param sessionId 세션 식별자
     */
    fun delete(sessionId: String)
}
