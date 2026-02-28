package com.arc.reactor.scheduler

import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.Instant
import java.util.UUID

/**
 * JDBC-backed implementation of [ScheduledJobExecutionStore].
 * Persists execution history to the `scheduled_job_executions` table.
 *
 * Annotated with [@Primary] so it takes precedence over the in-memory store
 * when a [JdbcTemplate] is available.
 */
@Primary
class JdbcScheduledJobExecutionStore(
    private val jdbcTemplate: JdbcTemplate
) : ScheduledJobExecutionStore {

    private val rowMapper = RowMapper<ScheduledJobExecution> { rs, _ ->
        ScheduledJobExecution(
            id = rs.getString("id"),
            jobId = rs.getString("job_id"),
            jobName = rs.getString("job_name"),
            status = JobExecutionStatus.valueOf(rs.getString("status")),
            result = rs.getString("result"),
            durationMs = rs.getLong("duration_ms"),
            dryRun = rs.getBoolean("dry_run"),
            startedAt = rs.getTimestamp("started_at").toInstant(),
            completedAt = rs.getTimestamp("completed_at")?.toInstant()
        )
    }

    override fun save(execution: ScheduledJobExecution): ScheduledJobExecution {
        val id = execution.id.ifBlank { UUID.randomUUID().toString() }
        val saved = execution.copy(id = id)
        jdbcTemplate.update(
            """INSERT INTO scheduled_job_executions
               (id, job_id, job_name, status, result, duration_ms, dry_run, started_at, completed_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            saved.id, saved.jobId, saved.jobName, saved.status.name,
            saved.result?.take(50000), saved.durationMs, saved.dryRun,
            java.sql.Timestamp.from(saved.startedAt),
            saved.completedAt?.let { java.sql.Timestamp.from(it) }
        )
        return saved
    }

    override fun findByJobId(jobId: String, limit: Int): List<ScheduledJobExecution> =
        jdbcTemplate.query(
            "SELECT * FROM scheduled_job_executions WHERE job_id = ? ORDER BY started_at DESC LIMIT ?",
            rowMapper, jobId, limit
        )

    override fun findRecent(limit: Int): List<ScheduledJobExecution> =
        jdbcTemplate.query(
            "SELECT * FROM scheduled_job_executions ORDER BY started_at DESC LIMIT ?",
            rowMapper, limit
        )
}
