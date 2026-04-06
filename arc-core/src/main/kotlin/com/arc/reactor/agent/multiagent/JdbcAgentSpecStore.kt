package com.arc.reactor.agent.multiagent

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

private val logger = KotlinLogging.logger {}

/**
 * JDBC 기반 에이전트 스펙 저장소.
 *
 * PostgreSQL의 `agent_specs` 테이블에 에이전트 정의를 영속한다.
 * `tool_names`와 `keywords`는 JSON 배열 문자열로 저장된다.
 *
 * @param jdbc JdbcTemplate
 * @param objectMapper JSON 직렬화/역직렬화용
 *
 * @see AgentSpecStore 인터페이스
 * @see AgentSpecRecord 영속 레코드
 */
class JdbcAgentSpecStore(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper = ObjectMapper()
) : AgentSpecStore {

    private val rowMapper = RowMapper<AgentSpecRecord> { rs, _ ->
        AgentSpecRecord(
            id = rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description").orEmpty(),
            toolNames = parseJsonList(rs.getString("tool_names")),
            keywords = parseJsonList(rs.getString("keywords")),
            systemPrompt = rs.getString("system_prompt"),
            mode = rs.getString("mode"),
            enabled = rs.getBoolean("enabled"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    override fun list(): List<AgentSpecRecord> {
        return jdbc.query(SELECT_ALL, rowMapper)
    }

    override fun listEnabled(): List<AgentSpecRecord> {
        return jdbc.query("$SELECT_ALL WHERE enabled = TRUE", rowMapper)
    }

    override fun get(id: String): AgentSpecRecord? {
        return jdbc.query("$SELECT_ALL WHERE id = ?", rowMapper, id)
            .firstOrNull()
    }

    override fun save(record: AgentSpecRecord): AgentSpecRecord {
        jdbc.update(
            UPSERT_SQL,
            record.id,
            record.name,
            record.description,
            objectMapper.writeValueAsString(record.toolNames),
            objectMapper.writeValueAsString(record.keywords),
            record.systemPrompt,
            record.mode,
            record.enabled,
            java.sql.Timestamp.from(record.createdAt),
            java.sql.Timestamp.from(record.updatedAt)
        )
        logger.debug { "에이전트 스펙 저장: id=${record.id}, name=${record.name}" }
        return record
    }

    override fun delete(id: String) {
        jdbc.update("DELETE FROM agent_specs WHERE id = ?", id)
        logger.debug { "에이전트 스펙 삭제: id=$id" }
    }

    private fun parseJsonList(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return try {
            objectMapper.readValue(json, STRING_LIST_TYPE)
        } catch (e: Exception) {
            logger.warn { "JSON 배열 파싱 실패: ${json.take(50)}" }
            emptyList()
        }
    }

    companion object {
        private const val SELECT_ALL =
            "SELECT id, name, description, tool_names, keywords, " +
                "system_prompt, mode, enabled, created_at, updated_at " +
                "FROM agent_specs ORDER BY created_at"

        private const val UPSERT_SQL =
            """INSERT INTO agent_specs
               (id, name, description, tool_names, keywords,
                system_prompt, mode, enabled, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (id) DO UPDATE SET
                 name = EXCLUDED.name,
                 description = EXCLUDED.description,
                 tool_names = EXCLUDED.tool_names,
                 keywords = EXCLUDED.keywords,
                 system_prompt = EXCLUDED.system_prompt,
                 mode = EXCLUDED.mode,
                 enabled = EXCLUDED.enabled,
                 updated_at = EXCLUDED.updated_at"""

        private val STRING_LIST_TYPE = object : TypeReference<List<String>>() {}
    }
}
