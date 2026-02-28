package com.arc.reactor.scheduler

/**
 * Store for persisting [ScheduledJobExecution] history records.
 *
 * Implementations must be thread-safe.
 * The in-memory implementation caps total entries at 200.
 * The JDBC implementation persists to the `scheduled_job_executions` table.
 */
interface ScheduledJobExecutionStore {
    fun save(execution: ScheduledJobExecution): ScheduledJobExecution
    fun findByJobId(jobId: String, limit: Int = 20): List<ScheduledJobExecution>
    fun findRecent(limit: Int = 50): List<ScheduledJobExecution>
}
