package com.arc.reactor.rag.ingestion

import com.arc.reactor.agent.config.RagIngestionProperties
import java.time.Instant

/**
 * 유효 RAG 수집 정책 (전역, 관리자 관리).
 *
 * 어떤 Q&A 쌍을 지식 기반에 수집할지 결정하는 글로벌 정책이다.
 *
 * @param enabled 수집 기능 활성화 여부
 * @param requireReview 수집 전 관리자 리뷰 필수 여부
 * @param allowedChannels 수집을 허용하는 채널 목록 (빈 집합이면 모든 채널 허용)
 * @param minQueryChars 최소 쿼리 문자 수 (너무 짧은 쿼리는 지식으로 부적합)
 * @param minResponseChars 최소 응답 문자 수 (너무 짧은 응답은 지식으로 부적합)
 * @param blockedPatterns 수집을 차단하는 패턴 목록 (개인정보 등)
 * @param createdAt 정책 생성 시각
 * @param updatedAt 정책 마지막 수정 시각
 */
data class RagIngestionPolicy(
    val enabled: Boolean,
    val requireReview: Boolean,
    val allowedChannels: Set<String>,
    val minQueryChars: Int,
    val minResponseChars: Int,
    val blockedPatterns: Set<String>,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    companion object {
        /** 프로퍼티 설정에서 정책을 생성한다. */
        fun fromProperties(props: RagIngestionProperties): RagIngestionPolicy = RagIngestionPolicy(
            enabled = props.enabled,
            requireReview = props.requireReview,
            allowedChannels = props.allowedChannels,
            minQueryChars = props.minQueryChars,
            minResponseChars = props.minResponseChars,
            blockedPatterns = props.blockedPatterns
        )
    }
}

/**
 * RAG 수집 정책 저장소 인터페이스.
 * 런타임에 정책을 동적으로 변경할 수 있다.
 */
interface RagIngestionPolicyStore {
    /** 현재 정책을 조회한다. 없으면 null. */
    fun getOrNull(): RagIngestionPolicy?
    /** 정책을 저장(생성 또는 업데이트)한다. */
    fun save(policy: RagIngestionPolicy): RagIngestionPolicy
    /** 정책을 삭제한다. 삭제되면 true 반환. */
    fun delete(): Boolean
}
