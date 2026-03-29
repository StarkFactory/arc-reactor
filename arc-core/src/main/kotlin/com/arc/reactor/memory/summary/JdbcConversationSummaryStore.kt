package com.arc.reactor.memory.summary

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC 기반 [ConversationSummaryStore]. 요약을 영구 저장한다.
 *
 * UPSERT 시맨틱을 사용한다 (H2용 MERGE, PostgreSQL 호환).
 * Facts는 JSON 텍스트로 직렬화된다.
 *
 * `conversation_summaries` 테이블이 필요하다 — Flyway 마이그레이션 V20 참고.
 */
class JdbcConversationSummaryStore(
    private val jdbcTemplate: JdbcTemplate
) : ConversationSummaryStore {

    /** JavaTimeModule 등록으로 Instant 등 시간 타입의 올바른 직렬화/역직렬화를 보장 */
    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    override fun get(sessionId: String): ConversationSummary? {
        return jdbcTemplate.query(
            "SELECT session_id, narrative, facts_json, summarized_up_to, created_at, updated_at " +
                "FROM conversation_summaries WHERE session_id = ?",
            { rs: ResultSet, _: Int -> mapRow(rs) },
            sessionId
        ).firstOrNull()
    }

    override fun save(summary: ConversationSummary) {
        val factsJson = objectMapper.writeValueAsString(summary.facts)
        val now = Timestamp.from(Instant.now())

        // H2 MERGE 문으로 INSERT 또는 UPDATE를 원자적으로 수행
        jdbcTemplate.update(
            """
            MERGE INTO conversation_summaries cs
            USING (VALUES (?, ?, ?, ?, ?, ?))
                AS src(session_id, narrative, facts_json, summarized_up_to, created_at, updated_at)
            ON cs.session_id = src.session_id
            WHEN MATCHED THEN UPDATE SET
                narrative = src.narrative,
                facts_json = src.facts_json,
                summarized_up_to = src.summarized_up_to,
                updated_at = src.updated_at
            WHEN NOT MATCHED THEN INSERT
                (session_id, narrative, facts_json, summarized_up_to, created_at, updated_at)
                VALUES (src.session_id, src.narrative, src.facts_json,
                    src.summarized_up_to, src.created_at, src.updated_at)
            """.trimIndent(),
            summary.sessionId, summary.narrative, factsJson,
            summary.summarizedUpToIndex, now, now
        )
    }

    override fun delete(sessionId: String) {
        jdbcTemplate.update("DELETE FROM conversation_summaries WHERE session_id = ?", sessionId)
    }

    /** ResultSet을 ConversationSummary로 매핑한다. facts JSON 파싱 실패 시 빈 목록. */
    private fun mapRow(rs: ResultSet): ConversationSummary {
        val factsJson = rs.getString("facts_json")
        val facts: List<StructuredFact> = try {
            objectMapper.readValue(factsJson)
        } catch (e: Exception) {
            logger.warn(e) { "팩트 JSON 파싱 실패: session=${rs.getString("session_id")}" }
            emptyList()
        }

        return ConversationSummary(
            sessionId = rs.getString("session_id"),
            narrative = rs.getString("narrative"),
            facts = facts,
            summarizedUpToIndex = rs.getInt("summarized_up_to"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }
}
