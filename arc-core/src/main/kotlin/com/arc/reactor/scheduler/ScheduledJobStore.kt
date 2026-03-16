package com.arc.reactor.scheduler

/**
 * 스케줄 작업 정의를 위한 저장소 인터페이스.
 *
 * WHY: 인메모리/JDBC 구현을 교체할 수 있는 추상화.
 * DynamicSchedulerService가 이 인터페이스를 통해 작업을 관리한다.
 *
 * @see InMemoryScheduledJobStore 인메모리 구현
 * @see JdbcScheduledJobStore JDBC 영속 구현
 * @see DynamicSchedulerService 스케줄러 서비스
 */
interface ScheduledJobStore {

    companion object {
        /** 작업 저장소의 last_result에 저장되는 최대 문자 수 */
        const val RESULT_TRUNCATION_LIMIT = 5000
    }

    /** 모든 작업을 조회한다 */
    fun list(): List<ScheduledJob>
    /** ID로 작업을 찾는다 */
    fun findById(id: String): ScheduledJob?
    /** 이름으로 작업을 찾는다 */
    fun findByName(name: String): ScheduledJob?
    /** 새 작업을 저장한다 */
    fun save(job: ScheduledJob): ScheduledJob
    /** 기존 작업을 갱신한다 */
    fun update(id: String, job: ScheduledJob): ScheduledJob?
    /** 작업을 삭제한다 */
    fun delete(id: String)
    /** 작업의 마지막 실행 결과를 갱신한다 */
    fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?)
}
