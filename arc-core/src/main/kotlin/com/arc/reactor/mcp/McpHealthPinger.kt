package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpHealthProperties
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 */
class McpHealthPinger(
    private val mcpManager: McpManager,
    private val properties: McpHealthProperties,
    private val scope: CoroutineScope
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
     */
    private val lastReconnectAttempt = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

    /** Spring 컨테이너 종료 시 코루틴 스코프를 정리한다. */
    override fun close() {
        stop()
    }

    /**
     * 쿨다운을 적용하여 재연결을 시도한다.
     *
     * 마지막 재연결 시도로부터 [reconnectCooldownMs] 이내이면 건너뛴다.
     * 소켓 고갈 방지를 위해 빈번한 재연결 시도를 억제한다.
     */
    private suspend fun attemptReconnectWithCooldown(serverName: String) {
        val now = System.currentTimeMillis()
        val lastAttempt = lastReconnectAttempt[serverName] ?: 0L
        if (now - lastAttempt < reconnectCooldownMs) {
            logger.debug {
                "MCP '$serverName' 재연결 쿨다운 중 " +
                    "(${(reconnectCooldownMs - (now - lastAttempt)) / 1000}초 후 재시도 가능)"
            }
            return
        }
        lastReconnectAttempt[serverName] = now
        try {
            mcpManager.ensureConnected(serverName)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "MCP 서버 '$serverName' 재연결 실패" }
        }
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
     * 도구가 비었거나 조회 실패 시 재연결을 시도한다.
     */
    private suspend fun checkConnectedHealth(serverName: String) {
        try {
            val tools = mcpManager.getToolCallbacks(serverName)
            if (tools.isEmpty()) {
                logger.warn {
                    "MCP 서버 '$serverName'가 CONNECTED이나 도구 없음 — 재연결 시도"
                }
                attemptReconnectWithCooldown(serverName)
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
