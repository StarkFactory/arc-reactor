package com.arc.reactor.rag.ingestion

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference

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
        val existed = ref.get() != null
        ref.set(null)
        return existed
    }
}

class InMemoryRagIngestionCandidateStore : RagIngestionCandidateStore {
    private val byId = ConcurrentHashMap<String, RagIngestionCandidate>()
    private val byRunId = ConcurrentHashMap<String, String>()
    private val orderedIds = ConcurrentLinkedDeque<String>()

    override fun save(candidate: RagIngestionCandidate): RagIngestionCandidate {
        val existingId = byRunId[candidate.runId]
        if (existingId != null) {
            return byId[existingId] ?: candidate
        }
        byId[candidate.id] = candidate
        byRunId[candidate.runId] = candidate.id
        orderedIds.addFirst(candidate.id)
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

