package com.arc.reactor.scheduler

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

private const val MAX_ENTRIES = 200

/**
 * Thread-safe in-memory implementation of [ScheduledJobExecutionStore].
 * Retains up to [MAX_ENTRIES] entries total; oldest entries are evicted when the cap is exceeded.
 */
class InMemoryScheduledJobExecutionStore : ScheduledJobExecutionStore {

    private val executions = ConcurrentLinkedDeque<ScheduledJobExecution>()

    override fun save(execution: ScheduledJobExecution): ScheduledJobExecution {
        val id = execution.id.ifBlank { UUID.randomUUID().toString() }
        val saved = execution.copy(id = id)
        executions.addFirst(saved)
        while (executions.size > MAX_ENTRIES) {
            executions.pollLast()
        }
        return saved
    }

    override fun findByJobId(jobId: String, limit: Int): List<ScheduledJobExecution> =
        executions.filter { it.jobId == jobId }.take(limit)

    override fun findRecent(limit: Int): List<ScheduledJobExecution> =
        executions.take(limit)
}
