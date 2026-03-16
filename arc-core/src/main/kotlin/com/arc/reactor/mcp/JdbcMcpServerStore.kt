package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC 기반 MCP 서버 저장소 — 영속적 스토리지.
 *
 * MCP 서버 설정을 `mcp_servers` 테이블에 저장한다 — Flyway 마이그레이션 V7 참조.
 * `config` 필드(Map)는 JSON 텍스트로 직렬화된다.
 *
 * WHY: 운영 환경에서 MCP 서버 설정을 서버 재시작 이후에도 유지하기 위해
 * PostgreSQL에 영속 저장한다. REST API로 등록된 서버가 재시작 시 자동 복원된다.
 *
 * @param jdbcTemplate Spring JDBC 템플릿
 * @see McpServerStore 인터페이스 정의
 * @see InMemoryMcpServerStore 인메모리 대안
 */
class JdbcMcpServerStore(
    private val jdbcTemplate: JdbcTemplate
) : McpServerStore {

    override fun list(): List<McpServer> {
        return jdbcTemplate.query(
            """SELECT id, name, description, transport_type, config, version, auto_connect, created_at, updated_at
               FROM mcp_servers ORDER BY created_at ASC""",
            ROW_MAPPER
        )
    }

    override fun findByName(name: String): McpServer? {
        val results = jdbcTemplate.query(
            """SELECT id, name, description, transport_type, config, version, auto_connect, created_at, updated_at
               FROM mcp_servers WHERE name = ?""",
            ROW_MAPPER,
            name
        )
        return results.firstOrNull()
    }

    override fun save(server: McpServer): McpServer {
        jdbcTemplate.update(
            """
            INSERT INTO mcp_servers
                (id, name, description, transport_type, config, version, auto_connect, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            server.id,
            server.name,
            server.description,
            server.transportType.name,
            objectMapper.writeValueAsString(server.config),
            server.version,
            server.autoConnect,
            java.sql.Timestamp.from(server.createdAt),
            java.sql.Timestamp.from(server.updatedAt)
        )
        return server
    }

    override fun update(name: String, server: McpServer): McpServer? {
        val existing = findByName(name) ?: return null
        val updatedAt = Instant.now()

        jdbcTemplate.update(
            """UPDATE mcp_servers
               SET description = ?, transport_type = ?, config = ?, version = ?, auto_connect = ?, updated_at = ?
               WHERE name = ?""",
            server.description,
            server.transportType.name,
            objectMapper.writeValueAsString(server.config),
            server.version,
            server.autoConnect,
            java.sql.Timestamp.from(updatedAt),
            name
        )

        return existing.copy(
            description = server.description,
            transportType = server.transportType,
            config = server.config,
            version = server.version,
            autoConnect = server.autoConnect,
            updatedAt = updatedAt
        )
    }

    override fun delete(name: String) {
        jdbcTemplate.update("DELETE FROM mcp_servers WHERE name = ?", name)
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()

        /** ResultSet 행을 McpServer로 변환하는 RowMapper */
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            McpServer(
                id = rs.getString("id"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                transportType = McpTransportType.valueOf(rs.getString("transport_type")),
                config = parseConfig(rs.getString("config")),
                version = rs.getString("version"),
                autoConnect = rs.getBoolean("auto_connect"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }

        /** JSON 문자열을 설정 맵으로 파싱한다. 실패 시 빈 맵을 반환한다. */
        private fun parseConfig(json: String?): Map<String, Any> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                logger.warn(e) { "MCP 서버 설정 JSON 파싱 실패, 빈 맵 반환" }
                emptyMap()
            }
        }
    }
}
