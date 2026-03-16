package com.arc.reactor.agent.metrics

import java.time.Instant

/**
 * 운영 대시보드를 위한 최근 응답 신뢰 이벤트 조회 인터페이스.
 *
 * 출력 가드 거부/수정, 경계값 위반, 미검증 응답 등의
 * 신뢰 관련 이벤트를 조회하여 운영 상태를 모니터링한다.
 *
 * @see NoOpAgentMetrics 기본 구현체
 * @see MicrometerAgentMetrics Micrometer 기반 구현체
 */
interface RecentTrustEventReader {
    /** 최근 신뢰 이벤트를 최대 [limit]개 조회한다. */
    fun recentTrustEvents(limit: Int = 20): List<RecentTrustEvent> = emptyList()
    /** 미검증 응답 총 수를 조회한다. */
    fun unverifiedResponsesCount(): Long = 0
    /** 출력 가드에 의해 거부된 응답 수를 조회한다. */
    fun outputGuardRejectedCount(): Long = 0
    /** 출력 가드에 의해 수정된 응답 수를 조회한다. */
    fun outputGuardModifiedCount(): Long = 0
    /** 경계값 위반으로 실패한 응답 수를 조회한다. */
    fun boundaryFailuresCount(): Long = 0
    /** 응답 가치 요약 정보를 조회한다. */
    fun responseValueSummary(): ResponseValueSummary = ResponseValueSummary()
    /** 미답변 쿼리 상위 [limit]개를 조회한다. */
    fun topMissingQueries(limit: Int = 5): List<MissingQueryInsight> = emptyList()
}

/**
 * 최근 신뢰 이벤트 데이터.
 *
 * 출력 가드 거부/수정, 경계값 위반, 미검증 응답 등의 이벤트를 기록한다.
 *
 * @param occurredAt 이벤트 발생 시각
 * @param type 이벤트 유형 (output_guard, boundary_violation, unverified_response)
 * @param severity 심각도 (FAIL, WARN)
 * @param action 수행된 작업 (allowed, modified, rejected)
 * @param stage 이벤트가 발생한 단계
 * @param reason 이벤트 사유
 * @param violation 위반 유형
 * @param policy 적용된 정책
 * @param channel 채널
 * @param queryCluster 쿼리 클러스터 ID
 * @param queryLabel 쿼리 레이블
 */
data class RecentTrustEvent(
    val occurredAt: Instant = Instant.now(),
    val type: String,
    val severity: String,
    val action: String? = null,
    val stage: String? = null,
    val reason: String? = null,
    val violation: String? = null,
    val policy: String? = null,
    val channel: String? = null,
    val queryCluster: String? = null,
    val queryLabel: String? = null
)
