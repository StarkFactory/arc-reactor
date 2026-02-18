package com.arc.reactor.rag.ingestion

import java.time.Instant
import java.util.UUID

enum class RagIngestionCandidateStatus {
    PENDING,
    REJECTED,
    INGESTED
}

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

interface RagIngestionCandidateStore {
    fun save(candidate: RagIngestionCandidate): RagIngestionCandidate
    fun findById(id: String): RagIngestionCandidate?
    fun findByRunId(runId: String): RagIngestionCandidate?
    fun list(
        limit: Int = 100,
        status: RagIngestionCandidateStatus? = null,
        channel: String? = null
    ): List<RagIngestionCandidate>
    fun updateReview(
        id: String,
        status: RagIngestionCandidateStatus,
        reviewedBy: String,
        reviewComment: String?,
        ingestedDocumentId: String? = null
    ): RagIngestionCandidate?
}
