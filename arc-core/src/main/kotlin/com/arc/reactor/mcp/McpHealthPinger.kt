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
                pingAllConnectedServers()
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
     * 모든 CONNECTED 서버에 대해 헬스체크를 수행한다.
     *
     * 서버 상태가 CONNECTED이지만 도구 콜백이 비어있으면 연결이 끊어진 것으로 간주하고
     * 재연결을 시도한다.
     */
    internal suspend fun pingAllConnectedServers() {
        val servers = mcpManager.listServers()
        for (server in servers) {
            val status = mcpManager.getStatus(server.name)
            if (status != McpServerStatus.CONNECTED) continue

            try {
                val tools = mcpManager.getToolCallbacks(server.name)
                if (tools.isEmpty()) {
                    logger.warn {
                        "MCP 서버 '${server.name}'가 CONNECTED이나 도구 없음 — 재연결 시도"
                    }
                    mcpManager.ensureConnected(server.name)
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) {
                    "MCP 서버 '${server.name}' 헬스체크 중 오류 — 재연결 시도"
                }
                try {
                    mcpManager.ensureConnected(server.name)
                } catch (reconnectEx: Exception) {
                    reconnectEx.throwIfCancellation()
                    logger.error(reconnectEx) {
                        "MCP 서버 '${server.name}' 재연결 실패"
                    }
                }
            }
        }
    }
}
