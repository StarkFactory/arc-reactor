package com.arc.reactor.policy.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant

class JdbcToolPolicyStore(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate
) : ToolPolicyStore {

    override fun getOrNull(): ToolPolicy? {
        return jdbcTemplate.query(
            "SELECT id, enabled, write_tool_names, deny_write_channels, allow_write_tool_names_in_deny_channels, " +
                "allow_write_tool_names_by_channel, " +
                "deny_write_message, created_at, updated_at " +
                "FROM tool_policy WHERE id = ?",
            { rs: ResultSet, _: Int -> mapRow(rs) },
            DEFAULT_ID
        ).firstOrNull()
    }

    override fun save(policy: ToolPolicy): ToolPolicy {
        return transactionTemplate.execute {
            val existing = getOrNull()
            val now = Instant.now()
            val createdAt = existing?.createdAt ?: now
            val updated = policy.copy(createdAt = createdAt, updatedAt = now)

            if (existing == null) {
                jdbcTemplate.update(
                    "INSERT INTO tool_policy " +
                        "(id, enabled, write_tool_names, deny_write_channels, allow_write_tool_names_in_deny_channels, " +
                        "allow_write_tool_names_by_channel, " +
                        "deny_write_message, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    DEFAULT_ID,
                    updated.enabled,
                    objectMapper.writeValueAsString(updated.writeToolNames.toList()),
                    objectMapper.writeValueAsString(updated.denyWriteChannels.toList()),
                    objectMapper.writeValueAsString(updated.allowWriteToolNamesInDenyChannels.toList()),
                    objectMapper.writeValueAsString(updated.allowWriteToolNamesByChannel.mapValues { it.value.toList() }),
                    updated.denyWriteMessage,
                    java.sql.Timestamp.from(createdAt),
                    java.sql.Timestamp.from(now)
                )
            } else {
                jdbcTemplate.update(
                    "UPDATE tool_policy SET enabled = ?, write_tool_names = ?, deny_write_channels = ?, " +
                        "allow_write_tool_names_in_deny_channels = ?, allow_write_tool_names_by_channel = ?, " +
                        "deny_write_message = ?, updated_at = ? " +
                        "WHERE id = ?",
                    updated.enabled,
                    objectMapper.writeValueAsString(updated.writeToolNames.toList()),
                    objectMapper.writeValueAsString(updated.denyWriteChannels.toList()),
                    objectMapper.writeValueAsString(updated.allowWriteToolNamesInDenyChannels.toList()),
                    objectMapper.writeValueAsString(updated.allowWriteToolNamesByChannel.mapValues { it.value.toList() }),
                    updated.denyWriteMessage,
                    java.sql.Timestamp.from(now),
                    DEFAULT_ID
                )
            }

            updated
        } ?: error("Transaction returned null while saving tool policy")
    }

    override fun delete(): Boolean {
        val count = jdbcTemplate.update("DELETE FROM tool_policy WHERE id = ?", DEFAULT_ID)
        return count > 0
    }

    private fun mapRow(rs: ResultSet): ToolPolicy {
        return ToolPolicy(
            enabled = rs.getBoolean("enabled"),
            writeToolNames = parseJsonSet(rs.getString("write_tool_names")),
            denyWriteChannels = parseJsonSet(rs.getString("deny_write_channels")),
            allowWriteToolNamesInDenyChannels = parseJsonSet(rs.getString("allow_write_tool_names_in_deny_channels")),
            allowWriteToolNamesByChannel = parseJsonMap(rs.getString("allow_write_tool_names_by_channel")),
            denyWriteMessage = rs.getString("deny_write_message") ?: "",
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    private fun parseJsonSet(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return runCatching { objectMapper.readValue<List<String>>(json).map { it.trim() }.filter { it.isNotBlank() }.toSet() }
            .getOrDefault(emptySet())
    }

    private fun parseJsonMap(json: String?): Map<String, Set<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            objectMapper.readValue<Map<String, List<String>>>(json)
                .mapKeys { (k, _) -> k.trim() }
                .mapValues { (_, v) -> v.map { it.trim() }.filter { it.isNotBlank() }.toSet() }
                .filterKeys { it.isNotBlank() }
                .filterValues { it.isNotEmpty() }
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val DEFAULT_ID = "default"
        private val objectMapper = jacksonObjectMapper()
    }
}
