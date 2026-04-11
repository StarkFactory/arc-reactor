package com.arc.reactor.agent.checkpoint

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * [CheckpointStore]의 인메모리 구현.
 *
 * Caffeine bounded cache 기반으로 체크포인트를 저장한다.
 * 실행당 최대 체크포인트 수(ArrayDeque 내부)와 총 실행 수(외부 cache)를 모두 제한하여
 * 메모리 사용량을 관리한다. 코루틴 안전한 [Mutex]를 사용하여 캐리어 스레드 블로킹을 방지한다.
 *
 * R314 fix: 외부 `store`/`mutexes` 맵을 ConcurrentHashMap → Caffeine bounded cache로 전환.
 * 기존 구현은 내부 ArrayDeque의 `maxCheckpointsPerRun` 상한만 있었고 외부 맵은
 * 실행 수만큼 무제한 성장 가능했다. 이제 [maxRuns] 상한(기본 1000)으로 실행 자체도 제한.
 *
 * @param maxCheckpointsPerRun 실행당 최대 체크포인트 수. 초과 시 가장 오래된 항목 제거. 기본 50.
 * @param maxRuns 동시 추적할 최대 실행(runId) 수. 초과 시 W-TinyLFU로 evict. 기본 1000.
 */
class InMemoryCheckpointStore(
    private val maxCheckpointsPerRun: Int = DEFAULT_MAX_CHECKPOINTS_PER_RUN,
    maxRuns: Long = DEFAULT_MAX_RUNS
) : CheckpointStore {

    private val store: Cache<String, ArrayDeque<ExecutionCheckpoint>> = Caffeine.newBuilder()
        .maximumSize(maxRuns)
        .build()
    private val mutexes: Cache<String, Mutex> = Caffeine.newBuilder()
        .maximumSize(maxRuns)
        .build()

    override suspend fun save(checkpoint: ExecutionCheckpoint) {
        // Caffeine의 get(key, mappingFunction)은 atomic get-or-create이며 non-null 보장
        val checkpoints = store.get(checkpoint.runId) { ArrayDeque() }
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
        val checkpoints = store.getIfPresent(runId) ?: return emptyList()
        mutexFor(runId).withLock {
            return checkpoints.sortedBy { it.step }
        }
    }

    override suspend fun deleteByRunId(runId: String) {
        store.invalidate(runId)
        mutexes.invalidate(runId)
        logger.debug { "체크포인트 삭제: runId=$runId" }
    }

    /** runId별 Mutex를 반환한다. 없으면 생성. */
    private fun mutexFor(runId: String): Mutex =
        mutexes.get(runId) { Mutex() }

    /** 테스트 전용: Caffeine 지연 maintenance를 강제 실행한다. */
    internal fun forceCleanUp() {
        store.cleanUp()
        mutexes.cleanUp()
    }

    companion object {
        private const val DEFAULT_MAX_CHECKPOINTS_PER_RUN = 50
        const val DEFAULT_MAX_RUNS: Long = 1_000L
    }
}
