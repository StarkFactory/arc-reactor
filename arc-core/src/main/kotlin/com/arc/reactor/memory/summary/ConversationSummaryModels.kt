package com.arc.reactor.memory.summary

import java.time.Instant

/**
 * 대화 이력에서 추출된 구조화된 팩트.
 *
 * 서술형 요약에서 손실될 수 있는 정확한 정보(숫자, 조건, 결정)를 보존한다.
 *
 * @param key 팩트 식별자 (예: "order_number", "agreed_price")
 * @param value 정확한 값 (예: "#1234", "50,000 KRW")
 * @param category 그룹핑을 위한 의미 카테고리
 * @param extractedAt 이 팩트가 추출된 시각
 */
data class StructuredFact(
    val key: String,
    val value: String,
    val category: FactCategory = FactCategory.GENERAL,
    val extractedAt: Instant = Instant.now()
)

/**
 * 구조화된 팩트의 의미 카테고리.
 */
enum class FactCategory {
    /** 명명된 엔티티 (인물, 조직, 제품) */
    ENTITY,

    /** 대화 중 내린 결정 */
    DECISION,

    /** 합의된 조건이나 제약 */
    CONDITION,

    /** 현재 상태 */
    STATE,

    /** 수치 값 (가격, 수량, 날짜) */
    NUMERIC,

    /** 분류되지 않은 일반 팩트 */
    GENERAL
}

/**
 * 세션에 대해 영구 저장되는 대화 요약.
 *
 * 서술형 요약(흐름과 톤 캡처)과 구조화된 팩트(정확한 데이터 포인트 보존)를 결합한다.
 *
 * @param sessionId 이 요약이 속하는 세션 ID
 * @param narrative 대화 흐름의 자유 텍스트 요약
 * @param facts 추출된 구조화 팩트 목록
 * @param summarizedUpToIndex 요약된 메시지 수 (배타적 상한)
 * @param createdAt 이 요약이 처음 생성된 시각
 * @param updatedAt 이 요약이 마지막으로 갱신된 시각
 */
data class ConversationSummary(
    val sessionId: String,
    val narrative: String,
    val facts: List<StructuredFact>,
    val summarizedUpToIndex: Int,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * 요약 작업의 결과.
 *
 * @param narrative 자유 텍스트 요약
 * @param facts 추출된 구조화 팩트
 * @param tokenCost 요약 호출에 사용된 대략적 토큰 수
 */
data class SummarizationResult(
    val narrative: String,
    val facts: List<StructuredFact>,
    val tokenCost: Int = 0
)
