package com.arc.reactor.rag.ingestion

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant

class JdbcRagIngestionPolicyStore(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate
) : RagIngestionPolicyStore {

    override fun getOrNull(): RagIngestionPolicy? {
        return jdbcTemplate.query(
            "SELECT id, enabled, require_review, allowed_channels, min_query_chars, min_response_chars, " +
                "blocked_patterns, created_at, updated_at FROM rag_ingestion_policy WHERE id = ?",
            { rs: ResultSet, _: Int -> mapPolicy(rs) },
            DEFAULT_ID
        ).firstOrNull()
    }

    override fun save(policy: RagIngestionPolicy): RagIngestionPolicy {
        return transactionTemplate.execute {
            val existing = getOrNull()
            val now = Instant.now()
            val createdAt = existing?.createdAt ?: now
            val updated = policy.copy(createdAt = createdAt, updatedAt = now)

            if (existing == null) {
                jdbcTemplate.update(
                    "INSERT INTO rag_ingestion_policy " +
                        "(id, enabled, require_review, allowed_channels, min_query_chars, min_response_chars, " +
                        "blocked_patterns, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    DEFAULT_ID,
                    updated.enabled,
                    updated.requireReview,
                    objectMapper.writeValueAsString(updated.allowedChannels.toList()),
                    updated.minQueryChars,
                    updated.minResponseChars,
                    objectMapper.writeValueAsString(updated.blockedPatterns.toList()),
                    java.sql.Timestamp.from(createdAt),
                    java.sql.Timestamp.from(now)
                )
            } else {
                jdbcTemplate.update(
                    "UPDATE rag_ingestion_policy SET enabled = ?, require_review = ?, allowed_channels = ?, " +
                        "min_query_chars = ?, min_response_chars = ?, blocked_patterns = ?, updated_at = ? " +
                        "WHERE id = ?",
                    updated.enabled,
                    updated.requireReview,
                    objectMapper.writeValueAsString(updated.allowedChannels.toList()),
                    updated.minQueryChars,
                    updated.minResponseChars,
                    objectMapper.writeValueAsString(updated.blockedPatterns.toList()),
                    java.sql.Timestamp.from(now),
                    DEFAULT_ID
                )
            }
            updated
        } ?: error("Transaction returned null while saving RAG ingestion policy")
    }

    override fun delete(): Boolean {
        val count = jdbcTemplate.update("DELETE FROM rag_ingestion_policy WHERE id = ?", DEFAULT_ID)
        return count > 0
    }

    private fun mapPolicy(rs: ResultSet): RagIngestionPolicy {
        return RagIngestionPolicy(
            enabled = rs.getBoolean("enabled"),
            requireReview = rs.getBoolean("require_review"),
            allowedChannels = parseJsonSet(rs.getString("allowed_channels")).map { it.lowercase() }.toSet(),
            minQueryChars = rs.getInt("min_query_chars"),
            minResponseChars = rs.getInt("min_response_chars"),
            blockedPatterns = parseJsonSet(rs.getString("blocked_patterns")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    private fun parseJsonSet(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return runCatching {
            objectMapper.readValue<List<String>>(json)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    companion object {
        private const val DEFAULT_ID = "default"
        private val objectMapper = jacksonObjectMapper()
    }
}

class JdbcRagIngestionCandidateStore(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate
) : RagIngestionCandidateStore {

    override fun save(candidate: RagIngestionCandidate): RagIngestionCandidate {
        return transactionTemplate.execute {
            findByRunId(candidate.runId)?.let { return@execute it }
            jdbcTemplate.update(
                "INSERT INTO rag_ingestion_candidates " +
                    "(id, run_id, user_id, session_id, channel, query, response, status, " +
                    "captured_at, reviewed_at, reviewed_by, review_comment, ingested_document_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                candidate.id,
                candidate.runId,
                candidate.userId,
                candidate.sessionId,
                candidate.channel,
                candidate.query,
                candidate.response,
                candidate.status.name,
                java.sql.Timestamp.from(candidate.capturedAt),
                candidate.reviewedAt?.let { java.sql.Timestamp.from(it) },
                candidate.reviewedBy,
                candidate.reviewComment,
                candidate.ingestedDocumentId
            )
            findById(candidate.id) ?: candidate
        } ?: error("Transaction returned null while saving RAG ingestion candidate")
    }

    override fun findById(id: String): RagIngestionCandidate? {
        return jdbcTemplate.query(
            "SELECT id, run_id, user_id, session_id, channel, query, response, status, " +
                "captured_at, reviewed_at, reviewed_by, review_comment, ingested_document_id " +
                "FROM rag_ingestion_candidates WHERE id = ?",
            { rs: ResultSet, _: Int -> mapCandidate(rs) },
            id
        ).firstOrNull()
    }

    override fun findByRunId(runId: String): RagIngestionCandidate? {
        return jdbcTemplate.query(
            "SELECT id, run_id, user_id, session_id, channel, query, response, status, " +
                "captured_at, reviewed_at, reviewed_by, review_comment, ingested_document_id " +
                "FROM rag_ingestion_candidates WHERE run_id = ?",
            { rs: ResultSet, _: Int -> mapCandidate(rs) },
            runId
        ).firstOrNull()
    }

    override fun list(limit: Int, status: RagIngestionCandidateStatus?, channel: String?): List<RagIngestionCandidate> {
        val cappedLimit = limit.coerceIn(1, 500)
        val normalizedChannel = channel?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val where = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (status != null) {
            where.add("status = ?")
            params.add(status.name)
        }
        if (normalizedChannel != null) {
            where.add("LOWER(channel) = ?")
            params.add(normalizedChannel)
        }
        val whereClause = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
        val sql = "SELECT id, run_id, user_id, session_id, channel, query, response, status, " +
            "captured_at, reviewed_at, reviewed_by, review_comment, ingested_document_id " +
            "FROM rag_ingestion_candidates $whereClause ORDER BY captured_at DESC LIMIT ?"
        params.add(cappedLimit)
        return jdbcTemplate.query(sql, { rs: ResultSet, _: Int -> mapCandidate(rs) }, *params.toTypedArray())
    }

    override fun updateReview(
        id: String,
        status: RagIngestionCandidateStatus,
        reviewedBy: String,
        reviewComment: String?,
        ingestedDocumentId: String?
    ): RagIngestionCandidate? {
        val updatedAt = Instant.now()
        val count = jdbcTemplate.update(
            "UPDATE rag_ingestion_candidates SET status = ?, reviewed_at = ?, reviewed_by = ?, " +
                "review_comment = ?, ingested_document_id = ? WHERE id = ?",
            status.name,
            java.sql.Timestamp.from(updatedAt),
            reviewedBy,
            reviewComment,
            ingestedDocumentId,
            id
        )
        if (count == 0) return null
        return findById(id)
    }

    private fun mapCandidate(rs: ResultSet): RagIngestionCandidate {
        val reviewedAt = rs.getTimestamp("reviewed_at")?.toInstant()
        return RagIngestionCandidate(
            id = rs.getString("id"),
            runId = rs.getString("run_id"),
            userId = rs.getString("user_id"),
            sessionId = rs.getString("session_id"),
            channel = rs.getString("channel"),
            query = rs.getString("query"),
            response = rs.getString("response"),
            status = runCatching { RagIngestionCandidateStatus.valueOf(rs.getString("status")) }
                .getOrDefault(RagIngestionCandidateStatus.PENDING),
            capturedAt = rs.getTimestamp("captured_at").toInstant(),
            reviewedAt = reviewedAt,
            reviewedBy = rs.getString("reviewed_by"),
            reviewComment = rs.getString("review_comment"),
            ingestedDocumentId = rs.getString("ingested_document_id")
        )
    }
}
