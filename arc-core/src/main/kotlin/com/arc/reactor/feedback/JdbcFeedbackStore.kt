package com.arc.reactor.feedback

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC 기반 피드백 저장소 — 영속적 피드백 스토리지.
 *
 * 피드백을 `feedback` 테이블에 저장한다 — Flyway 마이그레이션 V17 참조.
 * 리스트 필드(toolsUsed, tags)는 JSON TEXT 컬럼으로 저장된다.
 *
 * WHY: 운영 환경에서 피드백 데이터를 서버 재시작 이후에도 유지하기 위해
 * PostgreSQL에 영속 저장한다. PromptLab 자동 최적화 파이프라인이
 * 이 데이터를 분석하여 프롬프트 개선 후보를 생성한다.
 *
 * @param jdbcTemplate Spring JDBC 템플릿
 * @see FeedbackStore 인터페이스 정의
 * @see InMemoryFeedbackStore 인메모리 대안 구현
 */
class JdbcFeedbackStore(
    private val jdbcTemplate: JdbcTemplate
) : FeedbackStore {

    /**
     * 피드백을 데이터베이스에 삽입한다.
     * toolsUsed, tags 등 리스트 필드는 JSON 문자열로 직렬화하여 저장한다.
     */
    override fun save(feedback: Feedback): Feedback {
        jdbcTemplate.update(
            """INSERT INTO feedback (
                feedback_id, query, response, rating, timestamp, comment,
                session_id, run_id, user_id, intent, domain, model,
                prompt_version, tools_used, duration_ms, tags, template_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
            feedback.tags?.let { objectMapper.writeValueAsString(it) },
            feedback.templateId
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

    /**
     * 동적 WHERE 절을 구성하여 필터링된 피드백을 조회한다.
     * WHY: 선택적 필터를 동적으로 조합해야 하므로 조건절과 파라미터를
     * 리스트로 구성한 뒤 한 번에 쿼리를 실행한다.
     */
    override fun list(
        rating: FeedbackRating?,
        from: Instant?,
        to: Instant?,
        intent: String?,
        sessionId: String?,
        templateId: String?
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
        if (templateId != null) {
            conditions.add("template_id = ?")
            params.add(templateId)
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

        /**
         * JSON 문자열을 문자열 리스트로 파싱한다.
         * 파싱 실패 시 경고 로그 후 null을 반환한다.
         *
         * @param json JSON 배열 문자열
         * @return 파싱된 리스트 또는 null
         */
        private fun parseJsonList(json: String?): List<String>? {
            if (json == null) return null
            return try {
                objectMapper.readValue<List<String>>(json)
            } catch (e: Exception) {
                logger.warn { "JSON 리스트 파싱 실패: $json" }
                null
            }
        }

        /**
         * ResultSet 행을 Feedback 객체로 변환하는 RowMapper.
         * prompt_version, duration_ms 등 nullable 필드는 getObject로 안전하게 읽는다.
         */
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
                tags = parseJsonList(rs.getString("tags")),
                templateId = rs.getString("template_id")
            )
        }
    }
}
