package com.arc.reactor.autoconfigure

import com.arc.reactor.mcp.McpManager
import com.arc.reactor.tool.SemanticToolSelector
import com.arc.reactor.tool.ToolSelector
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
    private val mcpManager: McpManager,
    private val toolSelector: ToolSelector? = null
) {

    @EventListener(ApplicationReadyEvent::class)
    fun initialize() {
        runBlocking { mcpManager.initializeFromStore() }
        prewarmSemanticToolSelector()
    }

    private fun prewarmSemanticToolSelector() {
        val semanticSelector = toolSelector as? SemanticToolSelector ?: return
        semanticSelector.prewarm(mcpManager.getAllToolCallbacks())
    }
}
