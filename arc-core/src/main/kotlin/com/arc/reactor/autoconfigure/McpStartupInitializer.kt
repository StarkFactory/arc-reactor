package com.arc.reactor.autoconfigure

import com.arc.reactor.mcp.McpManager
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * Initializes MCP servers on application startup.
 *
 * MCP server registration is managed via REST API and persisted in
 * [com.arc.reactor.mcp.McpServerStore]. On startup, this initializer restores
 * and auto-connects servers already present in the store.
 */
class McpStartupInitializer(
    private val mcpManager: McpManager
) {

    @EventListener(ApplicationReadyEvent::class)
    fun initialize() {
        runBlocking { mcpManager.initializeFromStore() }
    }
}
