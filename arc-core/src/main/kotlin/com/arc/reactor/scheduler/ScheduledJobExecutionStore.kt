package com.arc.reactor.scheduler

/**
 * [ScheduledJobExecution] 이력 기록을 영속하기 위한 저장소 인터페이스.
 *
 * 구현체는 스레드 안전해야 한다.
 * 인메모리 구현은 총 항목을 200개로 제한한다.
 *
 * WHY: 작업 실행 이력을 저장하여 운영자가 과거 실행 결과를 조회하고
 * 문제를 진단할 수 있게 한다.
 *
 * @see InMemoryScheduledJobExecutionStore 인메모리 구현
 * @see DynamicSchedulerService 실행 기록 저장 호출처
 */
interface ScheduledJobExecutionStore {
    /** 실행 기록을 저장한다 */
    fun save(execution: ScheduledJobExecution): ScheduledJobExecution
    /** 작업 ID별 실행 기록을 조회한다 (최신순) */
    fun findByJobId(jobId: String, limit: Int = 20): List<ScheduledJobExecution>
    /** 최근 실행 기록을 조회한다 */
    fun findRecent(limit: Int = 50): List<ScheduledJobExecution>

    /**
     * [jobId]에 대해 [keepCount]개만 유지하고 나머지 오래된 실행 기록을 삭제한다.
     * 현재 개수가 [keepCount]를 초과하지 않으면 아무 작업도 하지 않는다.
     */
    fun deleteOldestExecutions(jobId: String, keepCount: Int)
}
