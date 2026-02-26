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
        try {
            if (store.findByName(server.name) != null) return
            store.save(server)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist MCP server '${server.name}' to store" }
        }
    }

    fun delete(serverName: String) {
        if (store == null) return
        try {
            store.delete(serverName)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete MCP server '$serverName' from store" }
        }
    }

    fun loadAll(): List<McpServer> {
        if (store == null) return emptyList()
        return try {
            store.list()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load MCP servers from store, continuing with empty list" }
            emptyList()
        }
    }

    fun listOr(runtimeServers: Collection<McpServer>): List<McpServer> {
        if (store == null) return runtimeServers.toList()
        return try {
            store.list()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to list MCP servers from store, using runtime registry fallback" }
            runtimeServers.toList()
        }
    }
}
