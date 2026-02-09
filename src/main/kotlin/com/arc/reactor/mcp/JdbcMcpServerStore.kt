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
 * JDBC-based MCP server store for persistent storage.
 *
 * Stores MCP server configurations in the `mcp_servers` table — see Flyway migration V7.
 * The `config` field (Map) is serialized as JSON text.
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
            """INSERT INTO mcp_servers (id, name, description, transport_type, config, version, auto_connect, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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

        private fun parseConfig(json: String?): Map<String, Any> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse MCP server config JSON, returning empty map" }
                emptyMap()
            }
        }
    }
}
