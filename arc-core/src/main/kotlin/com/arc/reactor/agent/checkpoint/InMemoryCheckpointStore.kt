package com.arc.reactor.agent.checkpoint

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * [CheckpointStore]의 인메모리 구현.
 *
 * ConcurrentHashMap 기반으로 체크포인트를 저장한다.
 * 실행당 최대 체크포인트 수를 제한하여 메모리 사용량을 관리한다.
 * 코루틴 안전한 [Mutex]를 사용하여 캐리어 스레드 블로킹을 방지한다.
 *
 * @param maxCheckpointsPerRun 실행당 최대 체크포인트 수. 초과 시 가장 오래된 항목 제거. 기본 50.
 */
class InMemoryCheckpointStore(
    private val maxCheckpointsPerRun: Int = DEFAULT_MAX_CHECKPOINTS_PER_RUN
) : CheckpointStore {

    private val store = ConcurrentHashMap<String, ArrayDeque<ExecutionCheckpoint>>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    override suspend fun save(checkpoint: ExecutionCheckpoint) {
        val checkpoints = store.computeIfAbsent(checkpoint.runId) { ArrayDeque() }
        mutexFor(checkpoint.runId).withLock {
            checkpoints.addLast(checkpoint)
            // 최대 체크포인트 수 초과 시 가장 오래된 항목 제거 (O(1))
            if (checkpoints.size > maxCheckpointsPerRun) {
                val removed = checkpoints.removeFirst()
                logger.debug {
                    "체크포인트 제한 초과로 가장 오래된 항목 제거: runId=${checkpoint.runId}, removedStep=${removed.step}"
                }
            }
        }
        logger.debug { "체크포인트 저장: runId=${checkpoint.runId}, step=${checkpoint.step}" }
    }

    override suspend fun findByRunId(runId: String): List<ExecutionCheckpoint> {
        val checkpoints = store[runId] ?: return emptyList()
        mutexFor(runId).withLock {
            return checkpoints.sortedBy { it.step }
        }
    }

    override suspend fun deleteByRunId(runId: String) {
        store.remove(runId)
        mutexes.remove(runId)
        logger.debug { "체크포인트 삭제: runId=$runId" }
    }

    /** runId별 Mutex를 반환한다. 없으면 생성. */
    private fun mutexFor(runId: String): Mutex =
        mutexes.computeIfAbsent(runId) { Mutex() }

    companion object {
        private const val DEFAULT_MAX_CHECKPOINTS_PER_RUN = 50
    }
}
