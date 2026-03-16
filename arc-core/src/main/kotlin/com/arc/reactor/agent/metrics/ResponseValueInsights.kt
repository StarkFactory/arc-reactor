package com.arc.reactor.agent.metrics

import java.time.Instant
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 응답 가치 요약 — 관측된 응답의 품질 및 분류 통계.
 *
 * @param observedResponses 총 관측 응답 수
 * @param groundedResponses 근거 기반(grounded) 응답 수
 * @param blockedResponses 차단된 응답 수
 * @param interactiveResponses 대화형 응답 수
 * @param scheduledResponses 스케줄된 응답 수
 * @param answerModeCounts 답변 모드별 카운트
 * @param channelCounts 채널별 카운트
 * @param toolFamilyCounts 도구 패밀리별 카운트
 * @param laneSummaries 답변 모드별 레인 요약
 */
data class ResponseValueSummary(
    val observedResponses: Long = 0,
    val groundedResponses: Long = 0,
    val blockedResponses: Long = 0,
    val interactiveResponses: Long = 0,
    val scheduledResponses: Long = 0,
    val answerModeCounts: Map<String, Long> = emptyMap(),
    val channelCounts: Map<String, Long> = emptyMap(),
    val toolFamilyCounts: Map<String, Long> = emptyMap(),
    val laneSummaries: List<ResponseLaneSummary> = emptyList()
)

/**
 * 답변 모드별 레인 요약.
 *
 * @param answerMode 답변 모드 이름
 * @param observedResponses 관측 응답 수
 * @param groundedResponses 근거 기반 응답 수
 * @param blockedResponses 차단된 응답 수
 */
data class ResponseLaneSummary(
    val answerMode: String,
    val observedResponses: Long,
    val groundedResponses: Long,
    val blockedResponses: Long
)

/**
 * 미답변 쿼리 인사이트 — 차단된 쿼리의 클러스터 분석.
 *
 * @param queryCluster 쿼리 클러스터 ID (SHA-256 해시 기반)
 * @param queryLabel 쿼리 레이블 (사람이 읽을 수 있는 형식)
 * @param count 발생 횟수
 * @param lastOccurredAt 마지막 발생 시각
 * @param blockReason 차단 사유
 */
data class MissingQueryInsight(
    val queryCluster: String,
    val queryLabel: String,
    val count: Long,
    val lastOccurredAt: Instant,
    val blockReason: String? = null
)

/**
 * PII 보호를 위해 쿼리를 해시 기반 클러스터 ID로 익명화한 신호.
 *
 * @param clusterId SHA-256 해시의 앞 12자 (쿼리 식별용)
 * @param label 사람이 읽을 수 있는 레이블 (예: "Question cluster abc123")
 */
data class RedactedQuerySignal(
    val clusterId: String,
    val label: String
)

/** 공백을 정규화하기 위한 정규식. */
private val WHITESPACE_REGEX = Regex("\\s+")

/** SHA-256 다이제스트 — ThreadLocal로 스레드 안전성 보장. */
private val SHA256_DIGEST: ThreadLocal<MessageDigest> =
    ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

/**
 * 쿼리 미리보기를 익명화된 클러스터 신호로 변환한다.
 *
 * 쿼리를 정규화(소문자, 공백 정리)한 후 SHA-256 해시로 클러스터 ID를 생성한다.
 * 원본 쿼리 내용이 유출되지 않도록 해시만 사용한다.
 *
 * @param queryPreview 쿼리 미리보기 텍스트
 * @return 익명화된 쿼리 신호. 빈 쿼리이면 null
 */
fun redactQuerySignal(queryPreview: String): RedactedQuerySignal? {
    val normalized = normalizeQueryPreview(queryPreview)
    if (normalized.isBlank()) return null
    val clusterId = sha256Hex(normalized).take(12)
    val kind = if (normalized.endsWith("?")) "Question" else "Prompt"
    return RedactedQuerySignal(
        clusterId = clusterId,
        label = "$kind cluster $clusterId"
    )
}

/** 쿼리 미리보기를 정규화한다 (트림, 소문자 변환, 공백 정리). */
private fun normalizeQueryPreview(queryPreview: String): String {
    return queryPreview
        .trim()
        .lowercase()
        .replace(WHITESPACE_REGEX, " ")
}

/**
 * 응답 레인별 집계 — 답변 모드별 관측/근거 기반/차단 응답 수를 추적한다.
 * ConcurrentHashMap에서 사용하기 위해 원자적 카운터를 사용한다.
 */
internal data class ResponseLaneAggregate(
    val observedResponses: AtomicLong = AtomicLong(),
    val groundedResponses: AtomicLong = AtomicLong(),
    val blockedResponses: AtomicLong = AtomicLong()
)

/**
 * 미답변 쿼리별 집계 — 차단된 쿼리 클러스터별 카운트와 마지막 발생 시각을 추적한다.
 */
internal data class MissingQueryAggregate(
    val queryCluster: String,
    val queryLabel: String,
    val blockReason: String?,
    val count: AtomicLong = AtomicLong(),
    @Volatile var lastOccurredAt: Instant = Instant.now()
)

/** 입력 문자열의 SHA-256 해시를 16진수 문자열로 반환한다. */
private fun sha256Hex(input: String): String {
    val digest = SHA256_DIGEST.get()
    digest.reset()
    return digest.digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
