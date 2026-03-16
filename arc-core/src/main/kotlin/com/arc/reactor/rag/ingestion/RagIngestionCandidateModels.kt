package com.arc.reactor.rag.ingestion

import java.time.Instant
import java.util.UUID

/**
 * RAG 수집 후보의 상태를 나타내는 열거형.
 */
enum class RagIngestionCandidateStatus {
    /** 리뷰 대기 중 */
    PENDING,
    /** 리뷰에서 거절됨 */
    REJECTED,
    /** 수집 완료되어 지식 기반에 추가됨 */
    INGESTED
}

/**
 * RAG 수집 후보 데이터 모델.
 *
 * 에이전트 실행 결과 중 지식 기반에 추가할 만한 Q&A 쌍을 나타낸다.
 * 리뷰 프로세스를 거쳐 승인되면 Vector Store에 수집된다.
 *
 * @param id 고유 식별자 (자동 생성)
 * @param runId 에이전트 실행 ID (중복 수집 방지에 사용)
 * @param userId 쿼리를 수행한 사용자 ID
 * @param sessionId 대화 세션 ID (선택적)
 * @param channel 요청이 들어온 채널 (예: "slack", "web")
 * @param query 사용자의 원본 쿼리
 * @param response 에이전트의 응답 내용
 * @param status 현재 상태 (PENDING, REJECTED, INGESTED)
 * @param capturedAt 후보가 캡처된 시각
 * @param reviewedAt 리뷰된 시각 (선택적)
 * @param reviewedBy 리뷰어 ID (선택적)
 * @param reviewComment 리뷰 코멘트 (선택적)
 * @param ingestedDocumentId 수집된 문서 ID (INGESTED 상태일 때)
 */
data class RagIngestionCandidate(
    val id: String = UUID.randomUUID().toString(),
    val runId: String,
    val userId: String,
    val sessionId: String? = null,
    val channel: String? = null,
    val query: String,
    val response: String,
    val status: RagIngestionCandidateStatus = RagIngestionCandidateStatus.PENDING,
    val capturedAt: Instant = Instant.now(),
    val reviewedAt: Instant? = null,
    val reviewedBy: String? = null,
    val reviewComment: String? = null,
    val ingestedDocumentId: String? = null
)

/**
 * RAG 수집 후보 저장소 인터페이스.
 *
 * 후보의 CRUD 및 리뷰 상태 업데이트를 제공한다.
 */
interface RagIngestionCandidateStore {
    /** 후보를 저장한다. 같은 runId가 이미 존재하면 기존 후보를 반환한다. */
    fun save(candidate: RagIngestionCandidate): RagIngestionCandidate
    /** ID로 후보를 조회한다. */
    fun findById(id: String): RagIngestionCandidate?
    /** 실행 ID로 후보를 조회한다. */
    fun findByRunId(runId: String): RagIngestionCandidate?
    /** 후보 목록을 조회한다. 상태와 채널로 필터링 가능. */
    fun list(
        limit: Int = 100,
        status: RagIngestionCandidateStatus? = null,
        channel: String? = null
    ): List<RagIngestionCandidate>
    /** 리뷰 결과를 업데이트한다. */
    fun updateReview(
        id: String,
        status: RagIngestionCandidateStatus,
        reviewedBy: String,
        reviewComment: String?,
        ingestedDocumentId: String? = null
    ): RagIngestionCandidate?
}
