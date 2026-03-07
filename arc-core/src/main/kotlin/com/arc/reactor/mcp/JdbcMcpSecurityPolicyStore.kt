package com.arc.reactor.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant

class JdbcMcpSecurityPolicyStore(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate
) : McpSecurityPolicyStore {

    override fun getOrNull(): McpSecurityPolicy? {
        return jdbcTemplate.query(
            "SELECT id, allowed_server_names, max_tool_output_length, created_at, updated_at " +
                "FROM mcp_security_policy WHERE id = ?",
            { rs: ResultSet, _: Int -> mapRow(rs) },
            DEFAULT_ID
        ).firstOrNull()
    }

    override fun save(policy: McpSecurityPolicy): McpSecurityPolicy {
        return transactionTemplate.execute {
            val existing = getOrNull()
            val now = Instant.now()
            val createdAt = existing?.createdAt ?: now
            val saved = policy.copy(createdAt = createdAt, updatedAt = now)

            if (existing == null) {
                jdbcTemplate.update(
                    "INSERT INTO mcp_security_policy " +
                        "(id, allowed_server_names, max_tool_output_length, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    DEFAULT_ID,
                    objectMapper.writeValueAsString(saved.allowedServerNames.toList()),
                    saved.maxToolOutputLength,
                    java.sql.Timestamp.from(createdAt),
                    java.sql.Timestamp.from(now)
                )
            } else {
                jdbcTemplate.update(
                    "UPDATE mcp_security_policy SET allowed_server_names = ?, max_tool_output_length = ?, " +
                        "updated_at = ? WHERE id = ?",
                    objectMapper.writeValueAsString(saved.allowedServerNames.toList()),
                    saved.maxToolOutputLength,
                    java.sql.Timestamp.from(now),
                    DEFAULT_ID
                )
            }

            saved
        } ?: error("Transaction returned null while saving MCP security policy")
    }

    override fun delete(): Boolean {
        val count = jdbcTemplate.update("DELETE FROM mcp_security_policy WHERE id = ?", DEFAULT_ID)
        return count > 0
    }

    private fun mapRow(rs: ResultSet): McpSecurityPolicy {
        return McpSecurityPolicy(
            allowedServerNames = parseJsonSet(rs.getString("allowed_server_names")),
            maxToolOutputLength = rs.getInt("max_tool_output_length"),
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
