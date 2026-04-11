package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 도구 가용성 검사 결과.
 *
 * @param available 사용 가능한 도구 이름 목록
 * @param unavailable 사용 불가능한 도구 이름 목록 (MCP 서버 미연결 또는 미등록)
 * @param degraded 성능 저하 상태의 도구 이름 목록 (서버 FAILED 상태이나 캐시된 콜백 존재)
 */
data class ToolAvailabilityResult(
    val available: List<String>,
    val unavailable: List<String>,
    val degraded: List<String>
) {
    /** 모든 요청 도구가 사용 가능한지 여부 */
    val allAvailable: Boolean get() = unavailable.isEmpty() && degraded.isEmpty()
}

/**
 * MCP 도구 가용성 사전검사기.
 *
 * ReAct 루프 진입 전에 요청된 도구의 가용성을 확인하여
 * 고스트 도구 호출(존재하지 않는 도구를 호출하여 루프를 낭비하는 현상)을 방지한다.
 *
 * WHY: MCP 서버가 조용히 연결 해제되면 도구 콜백이 stale 상태로 남아있을 수 있다.
 * 사전검사를 통해 불가용 도구를 필터링하면 LLM의 불필요한 도구 호출 시도를 줄이고
 * 에이전트 실행 품질을 높인다.
 *
 * @param mcpManager MCP 서버 매니저 (도구 콜백 및 상태 조회용)
 */
class McpToolAvailabilityChecker(
    private val mcpManager: McpManager
) {

    /**
     * 요청된 도구 이름 목록의 가용성을 검사한다.
     *
     * 각 도구를 다음 기준으로 분류한다:
     * - **available**: MCP 콜백에 존재하고 해당 서버가 CONNECTED 상태
     * - **degraded**: MCP 콜백에 존재하나 해당 서버가 FAILED 상태 (캐시된 콜백)
     * - **unavailable**: MCP 콜백에 존재하지 않음
     *
     * @param requestedToolNames 검사할 도구 이름 목록
     * @return 도구 가용성 결과
     */
    fun check(requestedToolNames: List<String>): ToolAvailabilityResult {
        if (requestedToolNames.isEmpty()) {
            return ToolAvailabilityResult(
                available = emptyList(),
                unavailable = emptyList(),
                degraded = emptyList()
            )
        }

        // R279 fix: TOCTOU 윈도우 완화 — 이전에는 getAllToolCallbacks()(global 스냅샷)
        // 결과만으로 callbackNames를 구성했고, 그 후 listServers()를 호출했다. 두 호출
        // 사이에 서버가 reconnect하면 다음 reverse race가 발생할 수 있었다:
        //   - getAllToolCallbacks → []  (서버 막 disconnect 직전 snapshot)
        //   - listServers → server_A CONNECTED with tool_X  (즉시 reconnect)
        //   - 결과: tool_X NOT in callbackNames → unavailable로 잘못 분류
        // R279 fix: callbackNames를 두 source의 union으로 구성하여 양방향 race 모두 대응:
        //   - global 스냅샷에 있지만 per-server에 없음 → "floating tool" → available
        //   - per-server에 있지만 global 스냅샷에 없음 → server reconnect 직후 → available/degraded
        // 두 source가 같은 시점이 아니어도 union이 도구 존재 여부를 정확히 보존.
        val allCallbacks = mcpManager.getAllToolCallbacks()
        val globalCallbackNames = allCallbacks.map { it.name }.toSet()

        val servers = mcpManager.listServers()
        val serverStatusMap = buildServerStatusMap(servers)
        val toolToServerMap = buildToolToServerMap(servers)

        // R279 union: global 또는 per-server 어느 한 곳에라도 있으면 도구 존재로 간주
        val callbackNames = globalCallbackNames + toolToServerMap.keys

        val available = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        val degraded = mutableListOf<String>()

        for (toolName in requestedToolNames) {
            if (toolName !in callbackNames) {
                unavailable.add(toolName)
                continue
            }

            val serverName = toolToServerMap[toolName]
            if (serverName == null) {
                // 콜백은 global 스냅샷에 존재하나 어느 서버에도 매핑 안 됨 — "floating tool"
                // (예: 서버가 막 disconnect되어 per-server callbacks에서 사라졌으나 global
                // 캐시에 남아있는 경우, 또는 다른 메커니즘으로 등록된 stand-alone callback).
                // 전통적으로 available로 처리 (stale 캐시지만 호출 시도는 허용).
                available.add(toolName)
                continue
            }

            when (serverStatusMap[serverName]) {
                McpServerStatus.CONNECTED -> available.add(toolName)
                McpServerStatus.FAILED -> degraded.add(toolName)
                else -> unavailable.add(toolName)
            }
        }

        if (unavailable.isNotEmpty() || degraded.isNotEmpty()) {
            logger.warn {
                "MCP 도구 가용성 검사 결과 — " +
                    "available: ${available.size}, " +
                    "degraded: ${degraded.size}${ if (degraded.isNotEmpty()) " $degraded" else "" }, " +
                    "unavailable: ${unavailable.size}${ if (unavailable.isNotEmpty()) " $unavailable" else "" }"
            }
        }

        return ToolAvailabilityResult(
            available = available,
            unavailable = unavailable,
            degraded = degraded
        )
    }

    /** 서버별 현재 상태를 맵으로 구축한다 */
    private fun buildServerStatusMap(
        servers: List<McpServer>
    ): Map<String, McpServerStatus> {
        return servers.mapNotNull { server ->
            mcpManager.getStatus(server.name)?.let { status ->
                server.name to status
            }
        }.toMap()
    }

    /** 도구 이름 → 소속 서버 이름 매핑을 구축한다. 개별 서버 실패 시 건너뛴다. */
    private fun buildToolToServerMap(
        servers: List<McpServer>
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (server in servers) {
            val callbacks = try {
                mcpManager.getToolCallbacks(server.name)
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) {
                    "MCP 서버 '${server.name}' 도구 콜백 조회 실패 — 건너뜀"
                }
                continue
            }
            for (callback in callbacks) {
                map.putIfAbsent(callback.name, server.name)
            }
        }
        return map
    }
}
