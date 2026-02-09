package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Store for persisting MCP server configurations.
 *
 * Manages CRUD operations for MCP server registration data.
 * Used by [McpManager] to load server configs on startup
 * and by [McpServerController] for dynamic management via REST API.
 */
interface McpServerStore {

    /**
     * List all registered MCP servers.
     */
    fun list(): List<McpServer>

    /**
     * Find a server by its unique name.
     *
     * @param name Server name
     * @return Server config, or null if not found
     */
    fun findByName(name: String): McpServer?

    /**
     * Save a new MCP server configuration.
     *
     * @param server Server to save
     * @return Saved server (with generated id if not set)
     * @throws IllegalArgumentException if a server with the same name already exists
     */
    fun save(server: McpServer): McpServer

    /**
     * Update an existing MCP server configuration.
     *
     * @param name Current server name
     * @param server Updated server data
     * @return Updated server, or null if not found
     */
    fun update(name: String, server: McpServer): McpServer?

    /**
     * Delete an MCP server configuration.
     *
     * @param name Server name to delete
     */
    fun delete(name: String)
}

/**
 * In-memory implementation of [McpServerStore].
 *
 * Uses [ConcurrentHashMap] for thread-safe access.
 * Data is lost on application restart.
 */
class InMemoryMcpServerStore : McpServerStore {

    private val servers = ConcurrentHashMap<String, McpServer>()

    override fun list(): List<McpServer> {
        return servers.values.sortedBy { it.createdAt }
    }

    override fun findByName(name: String): McpServer? {
        return servers[name]
    }

    override fun save(server: McpServer): McpServer {
        require(!servers.containsKey(server.name)) { "MCP server '${server.name}' already exists" }
        val now = Instant.now()
        val toSave = server.copy(createdAt = now, updatedAt = now)
        servers[server.name] = toSave
        return toSave
    }

    override fun update(name: String, server: McpServer): McpServer? {
        val existing = servers[name] ?: return null
        val updated = server.copy(
            id = existing.id,
            name = name,
            createdAt = existing.createdAt,
            updatedAt = Instant.now()
        )
        servers[name] = updated
        return updated
    }

    override fun delete(name: String) {
        servers.remove(name)
    }
}
