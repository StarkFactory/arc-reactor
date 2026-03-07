package com.arc.reactor.scheduler

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [ScheduledJobStore] for local and DB-less environments.
 */
class InMemoryScheduledJobStore : ScheduledJobStore {

    private val jobs = ConcurrentHashMap<String, ScheduledJob>()

    override fun list(): List<ScheduledJob> = jobs.values.sortedBy { it.createdAt }

    override fun findById(id: String): ScheduledJob? = jobs[id]

    override fun findByName(name: String): ScheduledJob? = jobs.values.firstOrNull { it.name == name }

    override fun save(job: ScheduledJob): ScheduledJob {
        val now = Instant.now()
        val saved = job.copy(
            id = job.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = now,
            updatedAt = now
        )
        jobs[saved.id] = saved
        return saved
    }

    override fun update(id: String, job: ScheduledJob): ScheduledJob? {
        val existing = jobs[id] ?: return null
        val updated = job.copy(id = id, createdAt = existing.createdAt, updatedAt = Instant.now())
        jobs[id] = updated
        return updated
    }

    override fun delete(id: String) {
        jobs.remove(id)
    }

    override fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?) {
        val existing = jobs[id] ?: return
        jobs[id] = existing.copy(
            lastRunAt = Instant.now(),
            lastStatus = status,
            lastResult = result?.take(5000),
            updatedAt = Instant.now()
        )
    }
}
