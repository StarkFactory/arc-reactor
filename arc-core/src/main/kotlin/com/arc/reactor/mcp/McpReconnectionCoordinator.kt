package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

/**
 * MCP 서버 백그라운드 재연결 스케줄러.
 *
 * 연결 실패 시 지수 백오프(exponential backoff) + 지터(jitter)로 재연결을 시도한다.
 *
 * ## 재연결 전략
 * 1. 초기 지연: [McpReconnectionProperties.initialDelayMs]
 * 2. 지연 증가: 지연 * [McpReconnectionProperties.multiplier]^(시도-1)
 * 3. 최대 지연: [McpReconnectionProperties.maxDelayMs]
 * 4. 지터: 기본 지연의 75%-125% 대칭 랜덤 오프셋 (동시 재연결 시 부하 분산)
 * 5. 최대 시도: [McpReconnectionProperties.maxAttempts]회
 *
 * WHY: 고정 간격 재시도는 여러 서버가 동시에 실패할 때 일제히 재연결하여
 * 부하가 급증한다. 지수 백오프 + 지터로 이 "thundering herd" 문제를 방지한다.
 *
 * 재연결 중 서버가 이미 CONNECTED, DISCONNECTED 상태이거나 레지스트리에서 제거되면
 * 재연결 루프를 즉시 중단한다.
 *
 * @param scope 코루틴 스코프 (SupervisorJob 포함)
 * @param properties 재연결 설정
 * @param statusProvider 서버 현재 상태 조회 함수
 * @param serverExists 서버 레지스트리 존재 여부 확인 함수
 * @param reconnectAction 실제 재연결 수행 함수
 * @see DefaultMcpManager 재연결 코디네이터 활용
 */
internal class McpReconnectionCoordinator(
    private val scope: CoroutineScope,
    private val properties: McpReconnectionProperties,
    private val statusProvider: (String) -> McpServerStatus?,
    private val serverExists: (String) -> Boolean,
    private val reconnectAction: suspend (String) -> Boolean
) {

    /** 서버별 재연결 Job 관리 */
    private val reconnectionJobs = ConcurrentHashMap<String, Job>()

    /** 특정 서버의 재연결 Job을 취소한다 */
    fun clear(serverName: String) {
        reconnectionJobs.remove(serverName)?.cancel()
    }

    /** 모든 재연결 Job을 취소한다 */
    fun clearAll() {
        reconnectionJobs.values.forEach { it.cancel() }
        reconnectionJobs.clear()
    }

    /**
     * 서버에 대한 재연결을 스케줄링한다.
     *
     * 이미 활성 재연결 Job이 있으면 중복 스케줄링하지 않는다.
     * LAZY 시작으로 Job을 생성하여 putIfAbsent 경합에서 안전하게 처리한다.
     *
     * WHY: CoroutineStart.LAZY로 생성한 이유는, putIfAbsent 후 경합에서 진 Job을
     * 시작 전에 취소할 수 있기 때문이다. 즉시 시작하면 경합 Job이 이미 실행 중일 수 있다.
     */
    fun schedule(serverName: String) {
        if (!properties.enabled) return
        if (reconnectionJobs[serverName]?.isActive == true) return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val maxAttempts = properties.maxAttempts
            try {
                for (attempt in 1..maxAttempts) {
                    // 지수 백오프로 지연 시간을 계산한다
                    val baseDelay = minOf(
                        (properties.initialDelayMs * properties.multiplier.pow((attempt - 1).toDouble())).toLong(),
                        properties.maxDelayMs
                    )
                    // 75%-125% 대칭 지터를 적용한다 (항상 양수, 항상 랜덤)
                    val delayMs = (baseDelay * (0.75 + Math.random() * 0.5)).toLong()

                    logger.info {
                        "MCP 재연결 예약: '$serverName' " +
                            "(시도 $attempt/$maxAttempts, 지연 ${delayMs}ms)"
                    }

                    try {
                        delay(delayMs)
                    } catch (e: Exception) {
                        e.throwIfCancellation()
                        throw e
                    }

                    // 서버 상태 확인 — 이미 연결되었거나 제거된 경우 중단
                    val currentStatus = statusProvider(serverName)
                    if (!serverExists(serverName) ||
                        currentStatus == McpServerStatus.CONNECTED ||
                        currentStatus == McpServerStatus.DISCONNECTED
                    ) {
                        return@launch
                    }

                    val success = try {
                        reconnectAction(serverName)
                    } catch (e: Exception) {
                        e.throwIfCancellation()
                        logger.warn(e) { "재연결 시도 $attempt/$maxAttempts 실패: '$serverName'" }
                        false
                    }

                    if (success) {
                        logger.info { "MCP 서버 '$serverName' 재연결 성공 (시도 $attempt)" }
                        return@launch
                    }
                }
                logger.warn { "MCP 재연결 한도 소진 (${properties.maxAttempts}회): '$serverName'" }
            } finally {
                // 완료된 Job을 맵에서 제거한다 (현재 Job과 일치하는 경우에만)
                reconnectionJobs.remove(serverName, kotlinx.coroutines.currentCoroutineContext()[Job])
            }
        }

        // putIfAbsent로 경합을 안전하게 처리한다
        val existing = reconnectionJobs.putIfAbsent(serverName, job)
        if (existing != null) {
            // 이미 다른 Job이 등록되어 있으면 새 Job을 취소한다
            job.cancel()
            return
        }
        job.start()
    }
}
