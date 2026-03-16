package com.arc.reactor.scheduler

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/** 총 보유 항목 최대 수 */
private const val MAX_ENTRIES = 200

/**
 * [ScheduledJobExecutionStore]의 스레드 안전 인메모리 구현.
 * 총 [MAX_ENTRIES]개까지 항목을 보유하며, 한도 초과 시 가장 오래된 항목을 퇴출한다.
 *
 * WHY: DB 없이도 실행 이력 조회 기능을 제공하기 위한 기본 구현.
 * [ConcurrentLinkedDeque]로 최신 항목이 항상 앞에 위치하게 한다.
 *
 * @see ScheduledJobExecutionStore 인터페이스 정의
 */
class InMemoryScheduledJobExecutionStore : ScheduledJobExecutionStore {

    /** 최신 항목이 앞에 오는 양방향 큐 */
    private val executions = ConcurrentLinkedDeque<ScheduledJobExecution>()

    override fun save(execution: ScheduledJobExecution): ScheduledJobExecution {
        val id = execution.id.ifBlank { UUID.randomUUID().toString() }
        val saved = execution.copy(id = id)
        // 최신 항목을 앞에 추가한다
        executions.addFirst(saved)
        // 한도 초과 시 가장 오래된 항목을 제거한다
        while (executions.size > MAX_ENTRIES) {
            executions.pollLast()
        }
        return saved
    }

    override fun findByJobId(jobId: String, limit: Int): List<ScheduledJobExecution> =
        executions.filter { it.jobId == jobId }.take(limit)

    override fun findRecent(limit: Int): List<ScheduledJobExecution> =
        executions.take(limit)

    override fun deleteOldestExecutions(jobId: String, keepCount: Int) {
        val jobEntries = executions.filter { it.jobId == jobId }
        if (jobEntries.size <= keepCount) return
        val toRemove = jobEntries.drop(keepCount)
        executions.removeAll(toRemove.toSet())
    }
}
