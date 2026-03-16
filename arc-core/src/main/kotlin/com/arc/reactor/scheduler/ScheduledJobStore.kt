package com.arc.reactor.scheduler

/**
 * Store for scheduled job definitions.
 */
interface ScheduledJobStore {

    companion object {
        /** Maximum character length stored for last_result in the job store. */
        const val RESULT_TRUNCATION_LIMIT = 5000
    }

    fun list(): List<ScheduledJob>
    fun findById(id: String): ScheduledJob?
    fun findByName(name: String): ScheduledJob?
    fun save(job: ScheduledJob): ScheduledJob
    fun update(id: String, job: ScheduledJob): ScheduledJob?
    fun delete(id: String)
    fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?)
}
