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
        val jobTypeStr = rs.getString("job_type")
        ScheduledJob(
            id = rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            cronExpression = rs.getString("cron_expression"),
            timezone = rs.getString("timezone"),
            jobType = if (jobTypeStr != null) ScheduledJobType.valueOf(jobTypeStr) else ScheduledJobType.MCP_TOOL,
            mcpServerName = rs.getString("mcp_server_name"),
            toolName = rs.getString("tool_name"),
            toolArguments = if (argsJson.isNullOrBlank()) emptyMap() else mapper.readValue(argsJson),
            agentPrompt = rs.getString("agent_prompt"),
            personaId = rs.getString("persona_id"),
            agentSystemPrompt = rs.getString("agent_system_prompt"),
            agentModel = rs.getString("agent_model"),
            agentMaxToolCalls = rs.getObject("agent_max_tool_calls") as? Int,
            slackChannelId = rs.getString("slack_channel_id"),
            teamsWebhookUrl = rs.getString("teams_webhook_url"),
            retryOnFailure = rs.getBoolean("retry_on_failure"),
            maxRetryCount = rs.getInt("max_retry_count"),
            executionTimeoutMs = rs.getObject("execution_timeout_ms") as? Long,
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
            """INSERT INTO scheduled_jobs (
                id, name, description, cron_expression, timezone, job_type,
                mcp_server_name, tool_name, tool_arguments,
                agent_prompt, persona_id, agent_system_prompt, agent_model, agent_max_tool_calls,
                slack_channel_id, teams_webhook_url,
                retry_on_failure, max_retry_count, execution_timeout_ms,
                enabled, created_at, updated_at
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            saved.id, saved.name, saved.description, saved.cronExpression, saved.timezone,
            saved.jobType.name,
            saved.mcpServerName, saved.toolName, mapper.writeValueAsString(saved.toolArguments),
            saved.agentPrompt, saved.personaId, saved.agentSystemPrompt,
            saved.agentModel, saved.agentMaxToolCalls,
            saved.slackChannelId, saved.teamsWebhookUrl,
            saved.retryOnFailure, saved.maxRetryCount, saved.executionTimeoutMs,
            saved.enabled,
            java.sql.Timestamp.from(saved.createdAt), java.sql.Timestamp.from(saved.updatedAt)
        )
        return saved
    }

    override fun update(id: String, job: ScheduledJob): ScheduledJob? {
        val now = Instant.now()
        val rows = jdbcTemplate.update(
            """UPDATE scheduled_jobs SET
                name = ?, description = ?, cron_expression = ?, timezone = ?, job_type = ?,
                mcp_server_name = ?, tool_name = ?, tool_arguments = ?,
                agent_prompt = ?, persona_id = ?, agent_system_prompt = ?,
                agent_model = ?, agent_max_tool_calls = ?,
                slack_channel_id = ?, teams_webhook_url = ?,
                retry_on_failure = ?, max_retry_count = ?, execution_timeout_ms = ?,
                enabled = ?, updated_at = ?
               WHERE id = ?""",
            job.name, job.description, job.cronExpression, job.timezone, job.jobType.name,
            job.mcpServerName, job.toolName, mapper.writeValueAsString(job.toolArguments),
            job.agentPrompt, job.personaId, job.agentSystemPrompt,
            job.agentModel, job.agentMaxToolCalls,
            job.slackChannelId, job.teamsWebhookUrl,
            job.retryOnFailure, job.maxRetryCount, job.executionTimeoutMs,
            job.enabled, java.sql.Timestamp.from(now), id
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
