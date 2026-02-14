package com.arc.reactor.scheduler

/**
 * Store for scheduled job definitions.
 */
interface ScheduledJobStore {
    fun list(): List<ScheduledJob>
    fun findById(id: String): ScheduledJob?
    fun findByName(name: String): ScheduledJob?
    fun save(job: ScheduledJob): ScheduledJob
    fun update(id: String, job: ScheduledJob): ScheduledJob?
    fun delete(id: String)
    fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?)
}
