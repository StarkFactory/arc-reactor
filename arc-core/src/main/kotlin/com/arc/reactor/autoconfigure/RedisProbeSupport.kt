package com.arc.reactor.autoconfigure

import mu.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Spring 컨테이너 startup 중 Redis 가용성 probe 헬퍼.
 *
 * R290 fix: 이전 구현은 [java.util.concurrent.ForkJoinPool.commonPool]을 사용해
 * Redis probe를 5초 timeout으로 실행했으나, 다음 두 문제가 있었다:
 *
 * 1. **공유 풀 contamination**: `ForkJoinPool.commonPool()`은 JVM 전역 공유 풀로,
 *    parallel streams, `CompletableFuture.supplyAsync()` 등 다른 application 코드와 공유된다.
 *    Redis가 hang하면 commonPool 워커 스레드를 점유하여 다른 작업을 starve시킬 수 있다.
 * 2. **이중 probe**: SemanticCache와 TokenRevocationStore가 각각 자체 probe를 실행하면
 *    최악의 경우 startup 차단이 10초 이상으로 누적될 수 있다.
 *
 * R290 fix:
 * - 각 probe 호출마다 daemon single-thread executor를 생성하여 commonPool을 contaminate하지
 *   않는다. daemon thread이므로 application shutdown을 막지 않는다.
 * - probe 직후 [java.util.concurrent.ExecutorService.shutdownNow]로 즉시 정리하여 leak 없음.
 * - 두 configuration이 동일 헬퍼를 공유하여 동작 일관성 확보.
 */
internal object RedisProbeSupport {

    /** Redis probe 기본 timeout (초) */
    const val DEFAULT_PROBE_TIMEOUT_SECONDS: Long = 5

    /**
     * Redis 가용성을 probe한다.
     *
     * @param probeName 로깅용 probe 이름 (예: "시맨틱 캐시", "토큰 폐기 저장소")
     * @param probeAction Redis 호출 람다. 정상 완료하면 가용으로 간주.
     * @return probe 성공 시 true, 실패/timeout 시 false
     */
    fun isAvailable(
        probeName: String,
        probeAction: () -> Unit
    ): Boolean {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "arc-redis-probe-${probeName.replace(" ", "-")}").apply {
                isDaemon = true
            }
        }
        return try {
            val future = executor.submit<Boolean> {
                probeAction()
                true
            }
            future.get(DEFAULT_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn(e) { "$probeName 선택 중 Redis 연결 프로브 실패" }
            false
        } finally {
            executor.shutdownNow()
        }
    }
}
