package com.arc.reactor.rag.ingestion

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * [RagIngestionPolicyStore]의 인메모리 구현체.
 *
 * AtomicReference로 스레드 안전성을 보장한다.
 * 서버 재시작 시 데이터가 유실된다.
 *
 * @param initial 초기 정책 (선택적)
 */
class InMemoryRagIngestionPolicyStore(
    initial: RagIngestionPolicy? = null
) : RagIngestionPolicyStore {
    private val ref = AtomicReference<RagIngestionPolicy?>(initial)

    override fun getOrNull(): RagIngestionPolicy? = ref.get()

    override fun save(policy: RagIngestionPolicy): RagIngestionPolicy {
        val now = Instant.now()
        val createdAt = ref.get()?.createdAt ?: now
        val updated = policy.copy(createdAt = createdAt, updatedAt = now)
        ref.set(updated)
        return updated
    }

    override fun delete(): Boolean {
        return ref.getAndSet(null) != null
    }
}

/**
 * [RagIngestionCandidateStore]의 인메모리 구현체.
 *
 * ConcurrentHashMap과 ConcurrentLinkedDeque로 스레드 안전한 CRUD를 제공한다.
 * 최대 20,000개의 후보를 유지하며, 초과 시 가장 오래된 항목부터 제거한다.
 *
 * ## 자료구조 선택 이유
 * - byId: O(1) ID 조회
 * - byRunId: O(1) 실행 ID 기반 중복 검사
 * - orderedIds: 삽입 순서 유지 + 양방향 빠른 제거. Deque로 FIFO 퇴거 지원.
 */
class InMemoryRagIngestionCandidateStore : RagIngestionCandidateStore {
    /** ID → 후보 매핑 */
    private val byId = ConcurrentHashMap<String, RagIngestionCandidate>()
    /** 실행 ID → 후보 ID 매핑 (중복 수집 방지) */
    private val byRunId = ConcurrentHashMap<String, String>()
    /** 삽입 순서를 유지하는 ID 큐 (최신이 앞) */
    private val orderedIds = ConcurrentLinkedDeque<String>()

    override fun save(candidate: RagIngestionCandidate): RagIngestionCandidate {
        // 같은 runId가 이미 존재하면 중복 수집을 방지하고 기존 후보를 반환
        val existingId = byRunId[candidate.runId]
        if (existingId != null) {
            return byId[existingId] ?: candidate
        }
        byId[candidate.id] = candidate
        byRunId[candidate.runId] = candidate.id
        orderedIds.addFirst(candidate.id)
        // 크기 제한 초과 시 가장 오래된 항목을 제거
        while (orderedIds.size > 20000) {
            val old = orderedIds.pollLast() ?: break
            val removed = byId.remove(old)
            if (removed != null) byRunId.remove(removed.runId)
        }
        return candidate
    }

    override fun findById(id: String): RagIngestionCandidate? = byId[id]

    override fun findByRunId(runId: String): RagIngestionCandidate? = byRunId[runId]?.let { byId[it] }

    override fun list(limit: Int, status: RagIngestionCandidateStatus?, channel: String?): List<RagIngestionCandidate> {
        val size = limit.coerceIn(1, 1000)
        val ch = channel?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return orderedIds.asSequence()
            .mapNotNull { byId[it] }
            .filter { status == null || it.status == status }
            .filter { ch == null || it.channel?.lowercase() == ch }
            .take(size)
            .toList()
    }

    override fun updateReview(
        id: String,
        status: RagIngestionCandidateStatus,
        reviewedBy: String,
        reviewComment: String?,
        ingestedDocumentId: String?
    ): RagIngestionCandidate? {
        val existing = byId[id] ?: return null
        val updated = existing.copy(
            status = status,
            reviewedAt = Instant.now(),
            reviewedBy = reviewedBy,
            reviewComment = reviewComment,
            ingestedDocumentId = ingestedDocumentId
        )
        byId[id] = updated
        return updated
    }
}
