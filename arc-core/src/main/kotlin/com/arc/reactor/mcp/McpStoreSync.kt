package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Persistence synchronization helper for MCP server metadata.
 *
 * Keeps store-related concerns out of the runtime manager.
 */
internal class McpStoreSync(
    private val store: McpServerStore?
) {

    fun saveIfAbsent(server: McpServer) {
        if (store == null) return
        if (store.findByName(server.name) != null) return

        try {
            store.save(server)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist MCP server '${server.name}' to store" }
        }
    }

    fun delete(serverName: String) {
        store?.delete(serverName)
    }

    fun loadAll(): List<McpServer> {
        return store?.list().orEmpty()
    }

    fun listOr(runtimeServers: Collection<McpServer>): List<McpServer> {
        return store?.list() ?: runtimeServers.toList()
    }
}
