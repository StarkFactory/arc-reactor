package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.tool.ToolCallback
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.spec.McpSchema
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * MCP (Model Context Protocol) Server Manager
 *
 * Manages connections to MCP servers for dynamic tool loading.
 * Integrates with the official MCP SDK for protocol compliance.
 *
 * ## What is MCP?
 * Model Context Protocol is a standard for connecting AI agents to external tools
 * and data sources. It enables:
 * - Dynamic tool discovery and loading
 * - Standardized tool invocation
 * - Cross-platform tool sharing
 *
 * ## Supported Transports
 * - **STDIO**: Local process communication (most common)
 * - **SSE**: Server-Sent Events over HTTP
 * - **HTTP**: Streamable HTTP transport
 *
 * ## Example Usage
 * ```kotlin
 * val manager = DefaultMcpManager()
 *
 * // Register an MCP server
 * manager.register(McpServer(
 *     name = "filesystem",
 *     transportType = McpTransportType.STDIO,
 *     config = mapOf(
 *         "command" to "npx",
 *         "args" to listOf("-y", "@anthropic/mcp-server-filesystem")
 *     )
 * ))
 *
 * // Connect and load tools
 * manager.connect("filesystem")
 *
 * // Get tools for agent
 * val tools = manager.getAllToolCallbacks()
 * ```
 *
 * @see McpServer for server configuration
 * @see McpServerStatus for connection states
 */
interface McpManager {
    /**
     * Register an MCP server configuration.
     *
     * Does not connect immediately - use [connect] to establish connection.
     *
     * @param server Server configuration including transport settings
     */
    fun register(server: McpServer)

    /**
     * Connect to a registered MCP server.
     *
     * Establishes connection and loads available tools.
     *
     * @param serverName Name of the registered server
     * @return true if connection successful, false otherwise
     */
    suspend fun connect(serverName: String): Boolean

    /**
     * Disconnect from an MCP server.
     *
     * Releases resources and clears cached tools.
     *
     * @param serverName Name of the server to disconnect
     */
    suspend fun disconnect(serverName: String)

    /**
     * Get all tool callbacks from connected MCP servers.
     *
     * Returns tools as framework-agnostic callbacks.
     *
     * @return List of tool callbacks from all connected servers
     */
    fun getAllToolCallbacks(): List<ToolCallback>

    /**
     * Get tool callbacks from a specific MCP server.
     *
     * @param serverName Name of the server
     * @return List of tool callbacks, empty if not connected
     */
    fun getToolCallbacks(serverName: String): List<ToolCallback>

    /**
     * List all registered servers.
     *
     * @return List of server configurations (connected and disconnected)
     */
    fun listServers(): List<McpServer>

    /**
     * Get the connection status of a server.
     *
     * @param serverName Name of the server
     * @return Current status, null if server not registered
     */
    fun getStatus(serverName: String): McpServerStatus?
}

/**
 * Default MCP Manager Implementation
 *
 * Uses the official MCP SDK for protocol compliance.
 * Supports STDIO and SSE transports. HTTP (Streamable) awaits SDK support.
 *
 * ## Connection Flow
 * 1. Register server with [register]
 * 2. Connect with [connect] → initializes transport and loads tools
 * 3. Use tools via [getAllToolCallbacks] or [getToolCallbacks]
 * 4. Disconnect with [disconnect] when done
 *
 * ## Thread Safety
 * This implementation uses ConcurrentHashMap for thread-safe access.
 * Per-server locks ensure atomic connect/disconnect operations for the same server.
 */
class DefaultMcpManager(
    private val connectionTimeoutMs: Long = 30_000
) : McpManager, AutoCloseable {

    private val servers = ConcurrentHashMap<String, McpServer>()
    private val clients = ConcurrentHashMap<String, McpSyncClient>()
    private val toolCallbacksCache = ConcurrentHashMap<String, List<ToolCallback>>()
    private val statuses = ConcurrentHashMap<String, McpServerStatus>()
    private val serverLocks = ConcurrentHashMap<String, Any>()

    override fun register(server: McpServer) {
        logger.info { "Registering MCP server: ${server.name}" }
        servers[server.name] = server
        statuses[server.name] = McpServerStatus.PENDING
    }

    override suspend fun connect(serverName: String): Boolean {
        val server = servers[serverName] ?: run {
            logger.warn { "MCP server not found: $serverName" }
            return false
        }

        val lock = serverLocks.getOrPut(serverName) { Any() }
        return synchronized(lock) {
            try {
                logger.info { "Connecting to MCP server: $serverName" }
                statuses[serverName] = McpServerStatus.CONNECTING

                val client = when (server.transportType) {
                    McpTransportType.STDIO -> connectStdio(server)
                    McpTransportType.SSE -> connectSse(server)
                    McpTransportType.HTTP -> connectHttp(server)
                }

                if (client != null) {
                    clients[serverName] = client

                    // Load tool callbacks
                    val tools = loadToolCallbacks(client, serverName)
                    toolCallbacksCache[serverName] = tools

                    statuses[serverName] = McpServerStatus.CONNECTED
                    logger.info { "MCP server connected: $serverName with ${tools.size} tools" }
                    true
                } else {
                    statuses[serverName] = McpServerStatus.FAILED
                    false
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to connect MCP server: $serverName" }
                statuses[serverName] = McpServerStatus.FAILED
                false
            }
        }
    }

    /**
     * Connect via STDIO transport.
     */
    private fun connectStdio(server: McpServer): McpSyncClient? {
        val command = server.config["command"] as? String ?: run {
            logger.warn { "STDIO transport requires 'command' in config for server: ${server.name}" }
            return null
        }
        val args = (server.config["args"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        return try {
            val params = ServerParameters.builder(command)
                .args(*args.toTypedArray())
                .build()

            val transport = StdioClientTransport(params)

            val client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation(server.name, server.version ?: "1.0.0"))
                .build()

            // Initialize connection
            client.initialize()

            client
        } catch (e: Exception) {
            logger.error(e) { "Failed to create STDIO transport for ${server.name}" }
            null
        }
    }

    /**
     * Connect via SSE transport.
     */
    private fun connectSse(server: McpServer): McpSyncClient? {
        val url = server.config["url"] as? String ?: run {
            logger.warn { "SSE transport requires 'url' in config for server: ${server.name}" }
            return null
        }

        return try {
            val transport = HttpClientSseClientTransport.builder(url)
                .customizeClient { it.connectTimeout(java.time.Duration.ofMillis(connectionTimeoutMs)) }
                .build()

            val client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation(server.name, server.version ?: "1.0.0"))
                .build()

            client.initialize()
            client
        } catch (e: Exception) {
            logger.error(e) { "Failed to create SSE transport for ${server.name}" }
            null
        }
    }

    /**
     * Connect via HTTP (Streamable) transport.
     *
     * Note: Streamable HTTP transport is not available in MCP SDK 0.10.0.
     * Use SSE transport as an alternative. This will be implemented when
     * the SDK is upgraded to a version that includes HttpClientStreamableHttpTransport.
     */
    private fun connectHttp(server: McpServer): McpSyncClient? {
        logger.warn {
            "HTTP (Streamable) transport is not yet supported in MCP SDK 0.10.0. " +
            "Use SSE transport instead for server: ${server.name}"
        }
        return null
    }

    /**
     * Load tool callbacks from MCP server.
     */
    private fun loadToolCallbacks(client: McpSyncClient, serverName: String): List<ToolCallback> {
        return try {
            val toolsResult = client.listTools()
            val tools = toolsResult.tools()
            logger.info { "Loaded ${tools.size} tools from $serverName" }

            // Convert MCP tools to ToolCallback
            tools.map { tool ->
                McpToolCallback(
                    client = client,
                    name = tool.name(),
                    description = tool.description() ?: "",
                    mcpInputSchema = tool.inputSchema()
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load tools from $serverName" }
            emptyList()
        }
    }

    override suspend fun disconnect(serverName: String) {
        val lock = serverLocks.getOrPut(serverName) { Any() }
        synchronized(lock) {
            logger.info { "Disconnecting MCP server: $serverName" }

            clients.remove(serverName)?.let { client ->
                try {
                    client.closeGracefully()
                } catch (e: Exception) {
                    logger.warn(e) { "Error during graceful shutdown of $serverName, attempting force close" }
                    try {
                        client.close()
                    } catch (closeEx: Exception) {
                        logger.error(closeEx) { "Force close also failed for $serverName" }
                    }
                }
            }

            toolCallbacksCache.remove(serverName)
            statuses[serverName] = McpServerStatus.DISCONNECTED
        }
    }

    override fun getAllToolCallbacks(): List<ToolCallback> {
        return toolCallbacksCache.values.flatten()
    }

    override fun getToolCallbacks(serverName: String): List<ToolCallback> {
        return toolCallbacksCache[serverName] ?: emptyList()
    }

    override fun listServers(): List<McpServer> {
        return servers.values.toList()
    }

    override fun getStatus(serverName: String): McpServerStatus? {
        return statuses[serverName]
    }

    /**
     * Close all connected MCP servers and release resources.
     */
    override fun close() {
        logger.info { "Closing MCP Manager, disconnecting all servers" }
        for (serverName in clients.keys.toList()) {
            kotlinx.coroutines.runBlocking { disconnect(serverName) }
        }
        servers.clear()
        statuses.clear()
        serverLocks.clear()
    }
}

/**
 * MCP Tool Callback Wrapper
 *
 * Wraps an MCP tool as an Arc Reactor ToolCallback for unified tool handling.
 *
 * ## How It Works
 * 1. Receives tool call arguments as a Map
 * 2. Converts to MCP CallToolRequest
 * 3. Invokes tool via MCP client
 * 4. Extracts and returns text content
 *
 * @param client The MCP client connection
 * @param name Tool identifier
 * @param description Tool description for LLM
 * @param inputSchema JSON Schema for tool parameters (optional)
 */
class McpToolCallback(
    private val client: McpSyncClient,
    override val name: String,
    override val description: String,
    private val mcpInputSchema: McpSchema.JsonSchema?
) : ToolCallback {

    override val inputSchema: String
        get() = mcpInputSchema?.let {
            try {
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(it)
            } catch (e: Exception) {
                """{"type":"object","properties":{}}"""
            }
        } ?: """{"type":"object","properties":{}}"""

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        return try {
            val request = McpSchema.CallToolRequest(name, arguments)
            val result = client.callTool(request)

            // Convert result to string
            result.content().joinToString("\n") { content ->
                when (content) {
                    is McpSchema.TextContent -> content.text()
                    is McpSchema.ImageContent -> "[Image: ${content.mimeType()}]"
                    is McpSchema.EmbeddedResource -> "[Resource: ${content.resource().uri()}]"
                    else -> content.toString()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to call MCP tool: $name" }
            "Error: ${e.message}"
        }
    }
}
