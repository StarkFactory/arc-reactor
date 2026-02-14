package com.arc.reactor.scheduler

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.Instant
import java.util.UUID

/**
 * JDBC implementation of [ScheduledJobStore] backed by PostgreSQL.
 */
class JdbcScheduledJobStore(
    private val jdbcTemplate: JdbcTemplate
) : ScheduledJobStore {

    private val mapper = jacksonObjectMapper()

    private val rowMapper = RowMapper<ScheduledJob> { rs, _ ->
        val argsJson = rs.getString("tool_arguments")
        ScheduledJob(
            id = rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            cronExpression = rs.getString("cron_expression"),
            timezone = rs.getString("timezone"),
            mcpServerName = rs.getString("mcp_server_name"),
            toolName = rs.getString("tool_name"),
            toolArguments = if (argsJson.isNullOrBlank()) emptyMap() else mapper.readValue(argsJson),
            slackChannelId = rs.getString("slack_channel_id"),
            enabled = rs.getBoolean("enabled"),
            lastRunAt = rs.getTimestamp("last_run_at")?.toInstant(),
            lastStatus = rs.getString("last_status")?.let { JobExecutionStatus.valueOf(it) },
            lastResult = rs.getString("last_result"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    override fun list(): List<ScheduledJob> =
        jdbcTemplate.query("SELECT * FROM scheduled_jobs ORDER BY created_at", rowMapper)

    override fun findById(id: String): ScheduledJob? =
        jdbcTemplate.query("SELECT * FROM scheduled_jobs WHERE id = ?", rowMapper, id).firstOrNull()

    override fun findByName(name: String): ScheduledJob? =
        jdbcTemplate.query("SELECT * FROM scheduled_jobs WHERE name = ?", rowMapper, name).firstOrNull()

    override fun save(job: ScheduledJob): ScheduledJob {
        val id = job.id.ifBlank { UUID.randomUUID().toString() }
        val now = Instant.now()
        val saved = job.copy(id = id, createdAt = now, updatedAt = now)
        jdbcTemplate.update(
            """INSERT INTO scheduled_jobs (id, name, description, cron_expression, timezone,
               mcp_server_name, tool_name, tool_arguments, slack_channel_id, enabled, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            saved.id, saved.name, saved.description, saved.cronExpression, saved.timezone,
            saved.mcpServerName, saved.toolName, mapper.writeValueAsString(saved.toolArguments),
            saved.slackChannelId, saved.enabled,
            java.sql.Timestamp.from(saved.createdAt), java.sql.Timestamp.from(saved.updatedAt)
        )
        return saved
    }

    override fun update(id: String, job: ScheduledJob): ScheduledJob? {
        val now = Instant.now()
        val rows = jdbcTemplate.update(
            """UPDATE scheduled_jobs SET name = ?, description = ?, cron_expression = ?, timezone = ?,
               mcp_server_name = ?, tool_name = ?, tool_arguments = ?, slack_channel_id = ?,
               enabled = ?, updated_at = ? WHERE id = ?""",
            job.name, job.description, job.cronExpression, job.timezone,
            job.mcpServerName, job.toolName, mapper.writeValueAsString(job.toolArguments),
            job.slackChannelId, job.enabled, java.sql.Timestamp.from(now), id
        )
        return if (rows > 0) findById(id) else null
    }

    override fun delete(id: String) {
        jdbcTemplate.update("DELETE FROM scheduled_jobs WHERE id = ?", id)
    }

    override fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?) {
        val now = Instant.now()
        jdbcTemplate.update(
            "UPDATE scheduled_jobs SET last_run_at = ?, last_status = ?, last_result = ?, updated_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now), status.name, result?.take(5000), java.sql.Timestamp.from(now), id
        )
    }
}
