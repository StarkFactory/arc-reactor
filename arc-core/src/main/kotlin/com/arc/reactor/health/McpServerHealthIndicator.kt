package com.arc.reactor.health

import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.model.McpServerStatus
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

/**
 * Health indicator that reports the number and connection status of registered MCP servers.
 */
class McpServerHealthIndicator(
    private val mcpManager: McpManager
) : HealthIndicator {

    override fun health(): Health {
        val servers = mcpManager.listServers()
        val statusMap = servers.associate { it.name to (mcpManager.getStatus(it.name) ?: McpServerStatus.PENDING) }
        val connected = statusMap.count { it.value == McpServerStatus.CONNECTED }
        val failed = statusMap.count { it.value == McpServerStatus.FAILED }

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
