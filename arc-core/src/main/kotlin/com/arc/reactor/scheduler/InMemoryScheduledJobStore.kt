package com.arc.reactor.scheduler

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 로컬 및 DB 없는 환경을 위한 [ScheduledJobStore] 인메모리 구현.
 *
 * WHY: DB 없이도 스케줄러 기본 동작을 보장하기 위한 기본 구현.
 *
 * @see JdbcScheduledJobStore 운영 환경용 JDBC 구현
 */
class InMemoryScheduledJobStore : ScheduledJobStore {

    /** 작업 ID를 키로 하는 동시성 안전 맵 */
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

    /** 마지막 실행 결과를 갱신한다. 결과 텍스트는 잘림 한도로 잘라낸다. */
    override fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?) {
        val existing = jobs[id] ?: return
        jobs[id] = existing.copy(
            lastRunAt = Instant.now(),
            lastStatus = status,
            lastResult = result?.take(ScheduledJobStore.RESULT_TRUNCATION_LIMIT),
            updatedAt = Instant.now()
        )
    }
}
