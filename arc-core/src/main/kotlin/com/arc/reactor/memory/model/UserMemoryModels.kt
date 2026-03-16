package com.arc.reactor.memory.model

import java.time.Instant

/**
 * 개별 사용자의 장기 기억 레코드.
 *
 * 대화 세션을 넘어 지속되어 개인화된 컨텍스트를 제공한다.
 * Facts와 Preferences는 단순 키-값 쌍으로 저장된다.
 * 최근 토픽은 슬라이딩 윈도우로 대화 주제를 추적한다.
 *
 * @param userId 사용자 고유 식별자
 * @param facts 사실적 속성 — 예: "team" → "backend", "role" → "senior engineer"
 * @param preferences 사용자 선호도 — 예: "language" → "Korean", "detail_level" → "brief"
 * @param recentTopics 최근 대화 주제 목록 (최신이 마지막, maxRecentTopics로 제한)
 * @param updatedAt 마지막 업데이트 시각
 */
data class UserMemory(
    val userId: String,

    /** 사실적 속성 — 예: "team" → "backend", "role" → "senior engineer" */
    val facts: Map<String, String> = emptyMap(),

    /** 사용자 선호도 — 예: "language" → "Korean", "detail_level" → "brief" */
    val preferences: Map<String, String> = emptyMap(),

    /** 최근 대화 주제 (최신이 마지막, maxRecentTopics로 제한) */
    val recentTopics: List<String> = emptyList(),

    /** 마지막 업데이트 시각 */
    val updatedAt: Instant = Instant.now()
)
