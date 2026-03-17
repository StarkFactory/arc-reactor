package com.arc.reactor.agent.checkpoint

/**
 * 실행 체크포인트 저장소 인터페이스.
 *
 * ReAct 루프의 중간 상태를 저장하고 조회하는 기능을 제공한다.
 * 인메모리 기본 구현과 JDBC 구현 등으로 확장 가능하다.
 *
 * @see InMemoryCheckpointStore 기본 인메모리 구현
 */
interface CheckpointStore {

    /**
     * 체크포인트를 저장한다.
     *
     * @param checkpoint 저장할 실행 체크포인트
     */
    suspend fun save(checkpoint: ExecutionCheckpoint)

    /**
     * 특정 실행의 모든 체크포인트를 단계 순서대로 조회한다.
     *
     * @param runId 실행 식별자
     * @return 해당 실행의 체크포인트 목록 (step 오름차순)
     */
    suspend fun findByRunId(runId: String): List<ExecutionCheckpoint>

    /**
     * 특정 실행의 모든 체크포인트를 삭제한다.
     *
     * @param runId 실행 식별자
     */
    suspend fun deleteByRunId(runId: String)
}
