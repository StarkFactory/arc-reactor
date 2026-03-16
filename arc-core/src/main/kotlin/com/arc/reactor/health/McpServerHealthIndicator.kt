package com.arc.reactor.health

import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.model.McpServerStatus
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

/**
 * 등록된 MCP 서버들의 연결 상태를 보고하는 헬스 인디케이터.
 *
 * 연결 상태 판단 로직:
 * - 모든 서버 실패 + 서버 존재: DOWN
 * - 일부 서버 실패: DEGRADED (부분 장애)
 * - 모든 서버 정상 또는 서버 없음: UP
 *
 * WHY: MCP 서버는 에이전트의 도구 실행 능력을 제공하는 핵심 인프라이다.
 * 서버 연결 장애를 즉시 감지하여 운영자가 대응할 수 있도록 한다.
 * DEGRADED 상태를 별도로 두어 부분 장애와 전면 장애를 구분한다.
 *
 * @param mcpManager MCP 서버 매니저
 * @see McpManager MCP 서버 수명주기 관리
 * @see McpServerStatus MCP 서버 상태 열거형
 */
class McpServerHealthIndicator(
    private val mcpManager: McpManager
) : HealthIndicator {

    override fun health(): Health {
        val servers = mcpManager.listServers()
        // 각 서버의 현재 상태를 조회한다. 상태가 없으면 PENDING으로 간주한다.
        val statusMap = servers.associate { it.name to (mcpManager.getStatus(it.name) ?: McpServerStatus.PENDING) }
        val connected = statusMap.count { it.value == McpServerStatus.CONNECTED }
        val failed = statusMap.count { it.value == McpServerStatus.FAILED }

        // 상태 결정: 전체 실패=DOWN, 부분 실패=DEGRADED, 그 외=UP
        val builder = if (failed > 0 && connected == 0 && servers.isNotEmpty()) {
            Health.down()
        } else if (failed > 0) {
            Health.status("DEGRADED")
        } else {
            Health.up()
        }

        return builder
            .withDetail("totalServers", servers.size)
            .withDetail("connected", connected)
            .withDetail("failed", failed)
            .withDetail("servers", statusMap)
            .build()
    }
}
