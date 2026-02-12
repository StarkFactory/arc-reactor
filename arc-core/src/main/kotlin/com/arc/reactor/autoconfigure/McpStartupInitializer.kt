package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.McpServerDefinition
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

private val logger = KotlinLogging.logger {}

/**
 * Initializes MCP servers on application startup.
 *
 * 1. Seeds yml-defined servers to the store (skip if already exists)
 * 2. Auto-connects servers marked with autoConnect=true
 */
class McpStartupInitializer(
    private val properties: AgentProperties,
    private val mcpManager: McpManager,
    private val mcpServerStore: McpServerStore
) {

    @EventListener(ApplicationReadyEvent::class)
    fun initialize() {
        seedYmlServers()
        runBlocking { mcpManager.initializeFromStore() }
    }

    private fun seedYmlServers() {
        val ymlServers = properties.mcp.servers
        if (ymlServers.isEmpty()) return

        logger.info { "Seeding ${ymlServers.size} MCP servers from yml config" }
        for (def in ymlServers) {
            if (def.name.isBlank()) {
                logger.warn { "Skipping MCP server definition with blank name" }
                continue
            }
            if (mcpServerStore.findByName(def.name) != null) {
                logger.debug { "MCP server '${def.name}' already in store, skipping yml seed" }
                continue
            }
            try {
                val server = def.toMcpServer()
                mcpServerStore.save(server)
                logger.info { "Seeded MCP server from yml: ${def.name} (${def.transport})" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to seed MCP server '${def.name}' from yml" }
            }
        }
    }
}

/**
 * Convert yml server definition to McpServer model.
 */
internal fun McpServerDefinition.toMcpServer(): McpServer {
    val config = buildMap<String, Any> {
        url?.let { put("url", it) }
        command?.let { put("command", it) }
        if (args.isNotEmpty()) put("args", args)
    }
    return McpServer(
        name = name,
        description = description,
        transportType = transport,
        config = config,
        autoConnect = autoConnect
    )
}
