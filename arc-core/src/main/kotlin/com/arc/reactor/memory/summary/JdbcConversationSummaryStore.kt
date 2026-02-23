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
 * JDBC-based [ConversationSummaryStore] for persistent summary storage.
 *
 * Uses UPSERT semantics (MERGE for H2, ON CONFLICT for PostgreSQL-compatible syntax).
 * Facts are serialized as JSON text.
 *
 * Requires the `conversation_summaries` table — see Flyway migration V20.
 */
class JdbcConversationSummaryStore(
    private val jdbcTemplate: JdbcTemplate
) : ConversationSummaryStore {

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

        val updated = jdbcTemplate.update(
            "UPDATE conversation_summaries SET narrative = ?, facts_json = ?, " +
                "summarized_up_to = ?, updated_at = ? WHERE session_id = ?",
            summary.narrative, factsJson, summary.summarizedUpToIndex, now, summary.sessionId
        )

        if (updated == 0) {
            jdbcTemplate.update(
                "INSERT INTO conversation_summaries (session_id, narrative, facts_json, " +
                    "summarized_up_to, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                summary.sessionId, summary.narrative, factsJson,
                summary.summarizedUpToIndex, now, now
            )
        }
    }

    override fun delete(sessionId: String) {
        jdbcTemplate.update("DELETE FROM conversation_summaries WHERE session_id = ?", sessionId)
    }

    private fun mapRow(rs: ResultSet): ConversationSummary {
        val factsJson = rs.getString("facts_json")
        val facts: List<StructuredFact> = try {
            objectMapper.readValue(factsJson)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse facts JSON for session ${rs.getString("session_id")}" }
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
