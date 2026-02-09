package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener

private val logger = KotlinLogging.logger {}

/**
 * Auto-connects to pre-configured MCP servers on application startup.
 *
 * Add/remove servers below. Connection failures are logged but do not block startup.
 */
@Configuration
class McpAutoConnectConfig(private val mcpManager: McpManager) {

    @EventListener(ApplicationReadyEvent::class)
    fun connectServers() {
        registerAndConnect(
            McpServer(
                name = "swagger-agent",
                description = "OpenAPI/Swagger spec loader and explorer",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse")
            )
        )
    }

    private fun registerAndConnect(server: McpServer) {
        mcpManager.register(server)
        try {
            runBlocking { mcpManager.connect(server.name) }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to connect MCP server '${server.name}' - it may not be running" }
        }
    }
}
