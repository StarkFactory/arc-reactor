package com.arc.reactor.scheduler

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Instant
import java.util.UUID

/**
 * 로컬 및 DB 없는 환경을 위한 [ScheduledJobStore] 인메모리 구현.
 *
 * WHY: DB 없이도 스케줄러 기본 동작을 보장하기 위한 기본 구현.
 *
 * R312 fix: ConcurrentHashMap → Caffeine bounded cache. 기존 구현은 `save()`가
 * 반복되면 무제한 성장 가능성이 있었다. 이제 [maxJobs] 상한(기본 1000)을 넘으면
 * W-TinyLFU 정책으로 evict.
 *
 * @see JdbcScheduledJobStore 운영 환경용 JDBC 구현
 */
class InMemoryScheduledJobStore(
    maxJobs: Long = DEFAULT_MAX_JOBS
) : ScheduledJobStore {

    /** 작업 ID를 키로 하는 Caffeine bounded cache */
    private val jobs: Cache<String, ScheduledJob> = Caffeine.newBuilder()
        .maximumSize(maxJobs)
        .build()

    override fun list(): List<ScheduledJob> = jobs.asMap().values.sortedBy { it.createdAt }

    override fun findById(id: String): ScheduledJob? = jobs.getIfPresent(id)

    override fun findByName(name: String): ScheduledJob? =
        jobs.asMap().values.firstOrNull { it.name == name }

    override fun save(job: ScheduledJob): ScheduledJob {
        val now = Instant.now()
        val saved = job.copy(
            id = job.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = now,
            updatedAt = now
        )
        jobs.put(saved.id, saved)
        return saved
    }

    override fun update(id: String, job: ScheduledJob): ScheduledJob? {
        val existing = jobs.getIfPresent(id) ?: return null
        val updated = job.copy(id = id, createdAt = existing.createdAt, updatedAt = Instant.now())
        jobs.put(id, updated)
        return updated
    }

    override fun delete(id: String) {
        jobs.invalidate(id)
    }

    /** 마지막 실행 결과를 갱신한다. 결과 텍스트는 잘림 한도로 잘라낸다. */
    override fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?) {
        val existing = jobs.getIfPresent(id) ?: return
        jobs.put(
            id,
            existing.copy(
                lastRunAt = Instant.now(),
                lastStatus = status,
                lastResult = result?.take(ScheduledJobStore.RESULT_TRUNCATION_LIMIT),
                updatedAt = Instant.now()
            )
        )
    }

    /** 테스트 전용: Caffeine 지연 maintenance를 강제 실행한다. */
    internal fun forceCleanUp() {
        jobs.cleanUp()
    }

    companion object {
        /** 기본 스케줄러 작업 상한. 초과 시 Caffeine W-TinyLFU 정책으로 evict. */
        const val DEFAULT_MAX_JOBS: Long = 1_000L
    }
}
