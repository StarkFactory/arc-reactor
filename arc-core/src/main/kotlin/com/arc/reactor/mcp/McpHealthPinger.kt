package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpHealthProperties
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.support.throwIfCancellation
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * MCP 서버 주기적 헬스체크 핑어.
 *
 * 연결된(CONNECTED) MCP 서버의 상태를 주기적으로 확인하여
 * 조용히 끊어진 연결을 사전에 감지하고 재연결을 트리거한다.
 *
 * WHY: MCP 서버가 조용히 연결 해제되면 ReAct 루프에서 "도구 없음" 오류가 발생한다.
 * 주기적 헬스체크로 이를 사전에 감지하여 고스트 도구 호출을 방지한다.
 *
 * @param mcpManager MCP 서버 매니저 (상태 조회 및 재연결 시도용)
 * @param properties 헬스체크 설정
 * @param scope 헬스체크 코루틴 실행 스코프
 * @param ownsScope true면 [close]에서 [scope]을 cancel한다 (production bean factory용).
 *                  false(기본)면 외부에서 관리하는 스코프(테스트의 backgroundScope 등)를
 *                  존중하여 cancel하지 않는다. R283 추가.
 */
class McpHealthPinger(
    private val mcpManager: McpManager,
    private val properties: McpHealthProperties,
    private val scope: CoroutineScope,
    private val ownsScope: Boolean = false
) : AutoCloseable {

    /** 헬스체크 루프 Job */
    @Volatile
    private var pingJob: Job? = null

    /**
     * 서버별 마지막 재연결 시도 시각 (에포크 밀리초).
     *
     * WHY: Health Pinger가 60초마다 ensureConnected()를 호출하면
     * 매번 새 HttpClient가 생성되어 TCP TIME_WAIT 소켓이 누적된다.
     * 최소 5분 간격으로 재연결 시도를 제한하여 소켓 고갈을 방지한다.
     *
     * R281: ConcurrentHashMap → Caffeine bounded cache 마이그레이션 (CLAUDE.md 준수).
     * 동적 MCP 서버 등록/해제 시나리오에서 unregister된 서버 키가 무한 누적되는 문제 방지.
     * R280 McpManager 4개 캐시 마이그레이션과 함께 MCP 영역 ConcurrentHashMap 위반 모두 제거.
     * MAX_TRACKED_SERVERS=1000은 일반 운영(1~10개)의 100배 헤드룸이며, eviction 발생 시
     * 운영 이상 신호. 쿨다운 정보가 evict되어도 다음 재연결 시도가 즉시 진행될 뿐 정확성에는
     * 영향 없음(쿨다운은 best-effort 소켓 보호 메커니즘).
     */
    private val lastReconnectAttempt: com.github.benmanes.caffeine.cache.Cache<String, Long> =
        Caffeine.newBuilder().maximumSize(MAX_TRACKED_SERVERS).build()

    /**
     * R331: 서버별 "한 번이라도 non-empty 도구 목록이 관찰되었는가" 플래그.
     *
     * **배경**: 이전 구현은 `CONNECTED + tools.isEmpty()`를 무조건 "조용히 끊어진 연결"로 간주하여
     * 재연결을 트리거했다. 그러나 MCP 프로토콜은 도구가 0개인 서버(resource/prompt만 제공하거나
     * 아직 도구를 등록하지 않은 서버)를 허용한다. 이런 서버는 `connect()`가 성공적으로 CONNECTED로
     * 전이되지만 도구 0개이므로 health check가 매 5분(쿨다운) 마다 재연결을 트리거 → 재연결도
     * 같은 결과 → 무한 루프. R283의 쿨다운은 spam을 완화할 뿐 루프 자체를 끊지 못한다.
     *
     * **수정 기준**: 재연결은 "퇴화(degradation)" 감지에만 사용. 즉 **이전에 non-empty였던 서버가
     * 지금 empty**라면 연결이 조용히 끊겼다고 판단한다. 한 번도 non-empty를 본 적 없는 서버는
     * 안정적 0-tool 서버로 간주하고 재연결 루프에 넣지 않는다.
     */
    private val seenNonEmptyServers: com.github.benmanes.caffeine.cache.Cache<String, Boolean> =
        Caffeine.newBuilder().maximumSize(MAX_TRACKED_SERVERS).build()

    /** 재연결 시도 최소 간격 (밀리초) — 기본 5분 */
    private val reconnectCooldownMs: Long = 300_000L

    /**
     * 헬스체크 루프를 시작한다.
     *
     * 이미 실행 중이면 중복 시작하지 않는다.
     * [McpHealthProperties.pingIntervalSeconds] 간격으로 모든 CONNECTED 서버를 점검한다.
     */
    fun start() {
        if (!properties.enabled) {
            logger.debug { "MCP 헬스체크가 비활성화됨" }
            return
        }
        if (pingJob?.isActive == true) {
            logger.debug { "MCP 헬스체크가 이미 실행 중" }
            return
        }

        pingJob = scope.launch {
            logger.info { "MCP 헬스체크 시작 (간격: ${properties.pingIntervalSeconds}초)" }
            while (isActive) {
                try {
                    delay(properties.pingIntervalSeconds * 1000L)
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    break
                }
                try {
                    pingAllConnectedServers()
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    logger.warn(e) { "헬스체크 실패, 다음 주기에 재시도" }
                }
            }
        }
    }

    /** 헬스체크 루프를 중지한다. */
    fun stop() {
        pingJob?.cancel()
        pingJob = null
        logger.info { "MCP 헬스체크 중지됨" }
    }

    /**
     * Spring 컨테이너 종료 시 코루틴 스코프를 정리한다.
     *
     * R283 fix: [ownsScope]가 true면 child pingJob 외에도 부모 스코프를 cancel하여
     * SupervisorJob과 dispatcher 참조까지 정리한다. 이전 구현은 child job만 cancel하여
     * 부모 SupervisorJob이 영원히 살아남는 leak이 있었다. 외부 주입 스코프(테스트의
     * backgroundScope)는 cancel하지 않아 호출자 lifecycle을 침해하지 않는다.
     */
    override fun close() {
        stop()
        if (ownsScope) {
            scope.cancel()
            logger.debug { "MCP 헬스체크 스코프 cancel 완료 (ownsScope=true)" }
        }
    }

    /**
     * 쿨다운을 적용하여 재연결을 시도한다.
     *
     * 마지막 재연결 시도로부터 [reconnectCooldownMs] 이내이면 건너뛴다.
     * 소켓 고갈 방지를 위해 빈번한 재연결 시도를 억제한다.
     */
    private suspend fun attemptReconnectWithCooldown(serverName: String) {
        val now = System.currentTimeMillis()
        val lastAttempt = lastReconnectAttempt.getIfPresent(serverName) ?: 0L
        if (now - lastAttempt < reconnectCooldownMs) {
            logger.debug {
                "MCP '$serverName' 재연결 쿨다운 중 " +
                    "(${(reconnectCooldownMs - (now - lastAttempt)) / 1000}초 후 재시도 가능)"
            }
            return
        }
        lastReconnectAttempt.put(serverName, now)
        try {
            mcpManager.ensureConnected(serverName)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "MCP 서버 '$serverName' 재연결 실패" }
        }
    }

    companion object {
        /**
         * R281: Caffeine bounded cache 최대 추적 서버 수.
         * 일반 운영은 1~10개이므로 1000은 사실상 unbounded와 동일.
         * 초과 시 LRU eviction으로 가장 오래된 쿨다운 정보가 evict되며,
         * 그 자체가 운영 이상 신호(서버가 1000개 이상 등록되었거나 churn 폭증).
         */
        private const val MAX_TRACKED_SERVERS = 1000L
    }

    /**
     * 모든 서버를 점검하고 필요 시 재연결을 시도한다.
     *
     * 점검 대상:
     * - CONNECTED + 도구 콜백 비어있음 → 조용히 끊어진 연결로 판단, 재연결
     * - FAILED → 일시적 장애 후 자동 복구 시도 (쿨다운 적용)
     * - PENDING → 시작 시 등록만 되고 connect 안 된 상태, 첫 연결 시도
     *
     * 쿨다운: [reconnectCooldownMs] 간격으로만 재시도하여 소켓 고갈 방지.
     */
    internal suspend fun pingAllConnectedServers() {
        val servers = mcpManager.listServers()
        for (server in servers) {
            val status = mcpManager.getStatus(server.name)
            when (status) {
                McpServerStatus.CONNECTED -> checkConnectedHealth(server.name)
                McpServerStatus.FAILED, McpServerStatus.PENDING -> {
                    logger.info {
                        "MCP 서버 '${server.name}' 상태=$status — 자동 재연결 시도 (쿨다운 적용)"
                    }
                    attemptReconnectWithCooldown(server.name)
                }
                else -> Unit
            }
        }
    }

    /**
     * CONNECTED 상태 서버의 도구 콜백 가용성을 점검한다.
     *
     * R331: 도구 개수가 **0에서 0**으로 유지되는 서버는 안정적인 0-tool 서버로 간주하고
     * 재연결하지 않는다. **non-empty에서 empty로 퇴화**한 경우에만 재연결을 트리거한다.
     * 이로써 유효한 0-tool MCP 서버가 5분마다 무한 재연결 루프에 빠지는 버그를 차단한다.
     * [seenNonEmptyServers] KDoc 참조.
     */
    private suspend fun checkConnectedHealth(serverName: String) {
        try {
            val tools = mcpManager.getToolCallbacks(serverName)
            if (tools.isNotEmpty()) {
                // 도구가 있으면 baseline 마킹 후 종료 — 이후 empty 전이 시 degradation 감지
                seenNonEmptyServers.put(serverName, true)
                return
            }
            // R331: empty는 "이전에 non-empty였을 때만" degradation
            if (seenNonEmptyServers.getIfPresent(serverName) == true) {
                logger.warn {
                    "MCP 서버 '$serverName'가 이전에 도구가 있었으나 현재 비어있음 — 재연결 시도"
                }
                // baseline 리셋 후 다음 재연결 주기부터 새 상태 재평가
                seenNonEmptyServers.invalidate(serverName)
                attemptReconnectWithCooldown(serverName)
            } else {
                logger.debug {
                    "MCP 서버 '$serverName' CONNECTED & 도구 0개 — 0-tool 서버로 간주, " +
                        "재연결 생략 (R331)"
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) {
                "MCP 서버 '$serverName' 헬스체크 중 오류 — 재연결 시도"
            }
            attemptReconnectWithCooldown(serverName)
        }
    }
}
