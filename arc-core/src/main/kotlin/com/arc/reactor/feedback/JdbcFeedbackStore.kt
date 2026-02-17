package com.arc.reactor.feedback

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC-based Feedback Store for persistent feedback storage.
 *
 * Stores feedback in the `feedback` table — see Flyway migration V17.
 * List fields (toolsUsed, tags) are stored as JSON TEXT columns.
 */
class JdbcFeedbackStore(
    private val jdbcTemplate: JdbcTemplate
) : FeedbackStore {

    override fun save(feedback: Feedback): Feedback {
        jdbcTemplate.update(
            """INSERT INTO feedback (
                feedback_id, query, response, rating, timestamp, comment,
                session_id, run_id, user_id, intent, domain, model,
                prompt_version, tools_used, duration_ms, tags
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            feedback.feedbackId,
            feedback.query,
            feedback.response,
            feedback.rating.name,
            java.sql.Timestamp.from(feedback.timestamp),
            feedback.comment,
            feedback.sessionId,
            feedback.runId,
            feedback.userId,
            feedback.intent,
            feedback.domain,
            feedback.model,
            feedback.promptVersion,
            feedback.toolsUsed?.let { objectMapper.writeValueAsString(it) },
            feedback.durationMs,
            feedback.tags?.let { objectMapper.writeValueAsString(it) }
        )
        return feedback
    }

    override fun get(feedbackId: String): Feedback? {
        val results = jdbcTemplate.query(
            "SELECT * FROM feedback WHERE feedback_id = ?",
            ROW_MAPPER,
            feedbackId
        )
        return results.firstOrNull()
    }

    override fun list(): List<Feedback> {
        return jdbcTemplate.query(
            "SELECT * FROM feedback ORDER BY timestamp DESC",
            ROW_MAPPER
        )
    }

    override fun list(
        rating: FeedbackRating?,
        from: Instant?,
        to: Instant?,
        intent: String?,
        sessionId: String?
    ): List<Feedback> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (rating != null) {
            conditions.add("rating = ?")
            params.add(rating.name)
        }
        if (from != null) {
            conditions.add("timestamp >= ?")
            params.add(java.sql.Timestamp.from(from))
        }
        if (to != null) {
            conditions.add("timestamp <= ?")
            params.add(java.sql.Timestamp.from(to))
        }
        if (intent != null) {
            conditions.add("intent = ?")
            params.add(intent)
        }
        if (sessionId != null) {
            conditions.add("session_id = ?")
            params.add(sessionId)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        val sql = "SELECT * FROM feedback$where ORDER BY timestamp DESC"

        return jdbcTemplate.query(sql, ROW_MAPPER, *params.toTypedArray())
    }

    override fun delete(feedbackId: String) {
        jdbcTemplate.update("DELETE FROM feedback WHERE feedback_id = ?", feedbackId)
    }

    override fun count(): Long {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feedback", Long::class.java) ?: 0L
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()

        private fun parseJsonList(json: String?): List<String>? {
            if (json == null) return null
            return try {
                objectMapper.readValue<List<String>>(json)
            } catch (e: Exception) {
                logger.warn { "Failed to parse JSON list: $json" }
                null
            }
        }

        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            Feedback(
                feedbackId = rs.getString("feedback_id"),
                query = rs.getString("query"),
                response = rs.getString("response"),
                rating = FeedbackRating.valueOf(rs.getString("rating")),
                timestamp = rs.getTimestamp("timestamp").toInstant(),
                comment = rs.getString("comment"),
                sessionId = rs.getString("session_id"),
                runId = rs.getString("run_id"),
                userId = rs.getString("user_id"),
                intent = rs.getString("intent"),
                domain = rs.getString("domain"),
                model = rs.getString("model"),
                promptVersion = rs.getObject("prompt_version") as? Int,
                toolsUsed = parseJsonList(rs.getString("tools_used")),
                durationMs = rs.getObject("duration_ms") as? Long,
                tags = parseJsonList(rs.getString("tags"))
            )
        }
    }
}
