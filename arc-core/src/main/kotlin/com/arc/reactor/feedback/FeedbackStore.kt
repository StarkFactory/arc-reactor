package com.arc.reactor.feedback

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 피드백 저장소 인터페이스
 *
 * 에이전트 응답에 대한 사용자 피드백의 CRUD 작업을 관리한다.
 *
 * WHY: 인터페이스를 분리하여 인메모리(기본)/JDBC(운영) 구현을 교체할 수 있게 한다.
 * `@ConditionalOnMissingBean`으로 등록되므로 사용자가 커스텀 구현으로 대체 가능하다.
 *
 * @see InMemoryFeedbackStore 기본 인메모리 구현
 * @see JdbcFeedbackStore JDBC 기반 영속 구현
 * @see com.arc.reactor.promptlab.analysis.FeedbackAnalyzer 피드백 분석기
 */
interface FeedbackStore {

    /**
     * 새 피드백 항목을 저장한다.
     *
     * @param feedback 저장할 피드백
     * @return 저장된 피드백
     */
    fun save(feedback: Feedback): Feedback

    /**
     * ID로 피드백 항목을 조회한다.
     *
     * @param feedbackId 피드백 고유 ID
     * @return 피드백이 존재하면 반환, 없으면 null
     */
    fun get(feedbackId: String): Feedback?

    /**
     * 모든 피드백 항목을 타임스탬프 내림차순으로 조회한다.
     *
     * @return 전체 피드백 목록
     */
    fun list(): List<Feedback>

    /**
     * 선택적 필터를 적용하여 피드백 항목을 조회한다.
     * 모든 필터는 AND 조합으로 적용된다. null은 해당 필드에 대한 필터 없음을 의미한다.
     *
     * @param rating 평점 필터
     * @param from 타임스탬프 >= from 필터
     * @param to 타임스탬프 <= to 필터
     * @param intent 인텐트 필터 (정확 일치)
     * @param sessionId 세션 ID 필터 (정확 일치)
     * @param templateId 프롬프트 템플릿 ID 필터 (정확 일치)
     * @return 필터링된 목록 (타임스탬프 내림차순)
     */
    fun list(
        rating: FeedbackRating? = null,
        from: Instant? = null,
        to: Instant? = null,
        intent: String? = null,
        sessionId: String? = null,
        templateId: String? = null
    ): List<Feedback>

    /**
     * ID로 피드백 항목을 삭제한다. 멱등성 — 존재하지 않아도 에러 없음.
     *
     * @param feedbackId 삭제할 피드백 ID
     */
    fun delete(feedbackId: String)

    /**
     * 전체 피드백 항목 수를 반환한다.
     *
     * @return 피드백 총 개수
     */
    fun count(): Long
}

/**
 * 인메모리 피드백 저장소
 *
 * [ConcurrentHashMap]을 사용한 스레드 안전 구현.
 * 영속적이지 않음 — 서버 재시작 시 데이터가 소실된다.
 *
 * WHY: 데이터베이스 없이도 기본 동작을 보장하기 위한 기본 구현.
 * 개발/테스트 환경에서 유용하며, 운영 환경에서는 JdbcFeedbackStore로 대체한다.
 *
 * @see JdbcFeedbackStore 운영 환경용 JDBC 구현
 */
class InMemoryFeedbackStore : FeedbackStore {

    /** 피드백 ID를 키로 하는 동시성 안전 맵 */
    private val entries = ConcurrentHashMap<String, Feedback>()

    override fun save(feedback: Feedback): Feedback {
        entries[feedback.feedbackId] = feedback
        return feedback
    }

    override fun get(feedbackId: String): Feedback? = entries[feedbackId]

    override fun list(): List<Feedback> {
        return entries.values.toList().sortedByDescending { it.timestamp }
    }

    /**
     * 필터 조건을 시퀀스 체이닝으로 적용한다.
     * WHY: 시퀀스를 사용하여 불필요한 중간 리스트 생성을 방지하고 메모리 효율을 높인다.
     */
    override fun list(
        rating: FeedbackRating?,
        from: Instant?,
        to: Instant?,
        intent: String?,
        sessionId: String?,
        templateId: String?
    ): List<Feedback> {
        return entries.values.toList()
            .asSequence()
            .filter { rating == null || it.rating == rating }
            .filter { from == null || !it.timestamp.isBefore(from) }
            .filter { to == null || !it.timestamp.isAfter(to) }
            .filter { intent == null || it.intent == intent }
            .filter { sessionId == null || it.sessionId == sessionId }
            .filter { templateId == null || it.templateId == templateId }
            .sortedByDescending { it.timestamp }
            .toList()
    }

    override fun delete(feedbackId: String) {
        entries.remove(feedbackId)
    }

    override fun count(): Long = entries.size.toLong()
}
