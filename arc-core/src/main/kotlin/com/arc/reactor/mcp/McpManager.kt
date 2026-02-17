package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
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
     * If a [McpServerStore] is configured, the server is also persisted.
     *
     * @param server Server configuration including transport settings
     */
    fun register(server: McpServer)

    /**
     * Synchronize a server configuration into runtime manager state.
     *
     * This updates the in-memory server config used by connect/reconnect flows.
     * It does not persist to store, and it does not auto-connect.
     *
     * @param server Updated server configuration
     */
    fun syncRuntimeServer(server: McpServer)

    /**
     * Unregister an MCP server.
     *
     * Disconnects if connected, removes from store and runtime cache.
     *
     * @param serverName Name of the server to remove
     */
    suspend fun unregister(serverName: String)

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

    /**
     * Ensure a server is connected, attempting reconnection if needed.
     *
     * If the server is in FAILED or DISCONNECTED state and auto-reconnection
     * is enabled, a single reconnection attempt is made synchronously.
     *
     * @param serverName Name of the server
     * @return true if the server is connected (either already or after reconnect)
     */
    suspend fun ensureConnected(serverName: String): Boolean

    /**
     * Initialize from store: load all servers and auto-connect those marked with autoConnect.
     *
     * Called on application startup to restore previously registered servers.
     */
    suspend fun initializeFromStore()
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
 * Per-server Mutex ensures atomic connect/disconnect operations for the same server.
 */
/**
 * MCP security configuration.
 *
 * @param allowedServerNames Allowlist of MCP server names. Empty = allow all.
 * @param maxToolOutputLength Maximum characters in tool output before truncation.
 */
data class McpSecurityConfig(
    val allowedServerNames: Set<String> = emptySet(),
    val maxToolOutputLength: Int = 50_000
)

class DefaultMcpManager(
    private val connectionTimeoutMs: Long = 30_000,
    private val securityConfig: McpSecurityConfig = McpSecurityConfig(),
    private val store: McpServerStore? = null,
    private val reconnectionProperties: McpReconnectionProperties = McpReconnectionProperties()
) : McpManager, AutoCloseable {

    private val servers = ConcurrentHashMap<String, McpServer>()
    private val clients = ConcurrentHashMap<String, McpSyncClient>()
    private val toolCallbacksCache = ConcurrentHashMap<String, List<ToolCallback>>()
    private val statuses = ConcurrentHashMap<String, McpServerStatus>()
    private val serverMutexes = ConcurrentHashMap<String, Mutex>()

    /** Tracks servers that already have a background reconnection task running. */
    private val reconnectingServers = ConcurrentHashMap.newKeySet<String>()

    /** Background scope for reconnection tasks. Uses SupervisorJob so one failure doesn't cancel others. */
    private val reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun mutexFor(serverName: String): Mutex = serverMutexes.getOrPut(serverName) { Mutex() }

    override fun register(server: McpServer) {
        if (securityConfig.allowedServerNames.isNotEmpty() &&
            server.name !in securityConfig.allowedServerNames
        ) {
            logger.warn { "MCP server rejected by allowlist: ${server.name}" }
            return
        }
        logger.info { "Registering MCP server: ${server.name}" }
        servers[server.name] = server
        statuses[server.name] = McpServerStatus.PENDING

        // Persist to store if available and not already saved
        if (store != null && store.findByName(server.name) == null) {
            try {
                store.save(server)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to persist MCP server '${server.name}' to store" }
            }
        }
    }

    override fun syncRuntimeServer(server: McpServer) {
        if (securityConfig.allowedServerNames.isNotEmpty() &&
            server.name !in securityConfig.allowedServerNames
        ) {
            logger.warn { "MCP runtime sync rejected by allowlist: ${server.name}" }
            return
        }
        servers[server.name] = server
        statuses.putIfAbsent(server.name, McpServerStatus.PENDING)
        logger.info { "Synchronized MCP runtime config for server: ${server.name}" }
    }

    override suspend fun unregister(serverName: String) {
        disconnectInternal(serverName)
        servers.remove(serverName)
        statuses.remove(serverName)
        serverMutexes.remove(serverName)
        store?.delete(serverName)
        logger.info { "Unregistered MCP server: $serverName" }
    }

    override suspend fun initializeFromStore() {
        val storeServers = store?.list().orEmpty()
        if (storeServers.isEmpty()) {
            logger.debug { "No MCP servers found in store" }
            return
        }

        logger.info { "Loading ${storeServers.size} MCP servers from store" }
        for (server in storeServers) {
            servers[server.name] = server
            statuses[server.name] = McpServerStatus.PENDING

            if (server.autoConnect) {
                try {
                    connect(server.name)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to auto-connect MCP server '${server.name}'" }
                }
            }
        }
    }

    override suspend fun connect(serverName: String): Boolean {
        val server = servers[serverName] ?: run {
            logger.warn { "MCP server not found: $serverName" }
            return false
        }

        return mutexFor(serverName).withLock {
            try {
                logger.info { "Connecting to MCP server: $serverName" }
                statuses[serverName] = McpServerStatus.CONNECTING
                val result = initializeClient(server, serverName)
                if (result) {
                    reconnectingServers.remove(serverName)
                } else {
                    scheduleReconnection(serverName)
                }
                result
            } catch (e: Exception) {
                logger.error(e) { "Failed to connect MCP server: $serverName" }
                statuses[serverName] = McpServerStatus.FAILED
                scheduleReconnection(serverName)
                false
            }
        }
    }

    private fun initializeClient(server: McpServer, serverName: String): Boolean {
        val client = when (server.transportType) {
            McpTransportType.STDIO -> connectStdio(server)
            McpTransportType.SSE -> connectSse(server)
            McpTransportType.HTTP -> connectHttp(server)
        }

        if (client == null) {
            statuses[serverName] = McpServerStatus.FAILED
            return false
        }

        clients[serverName] = client
        val tools = loadToolCallbacks(client, serverName)
        toolCallbacksCache[serverName] = tools
        statuses[serverName] = McpServerStatus.CONNECTED
        logger.info { "MCP server connected: $serverName with ${tools.size} tools" }
        return true
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
        if (command.contains("/") && !Files.exists(Paths.get(command))) {
            logger.warn { "STDIO command does not exist for server '${server.name}': $command" }
            return null
        }

        return try {
            val params = ServerParameters.builder(command)
                .args(*args.toTypedArray())
                .build()

            val transport = StdioClientTransport(params, JacksonMcpJsonMapper(ObjectMapper()))

            val client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(connectionTimeoutMs))
                .initializationTimeout(Duration.ofMillis(connectionTimeoutMs))
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
        val parsed = try {
            URI(url)
        } catch (e: Exception) {
            logger.warn { "Invalid SSE URL for server '${server.name}': $url" }
            return null
        }
        if (!parsed.isAbsolute || (parsed.scheme != "http" && parsed.scheme != "https")) {
            logger.warn { "SSE URL must be absolute http/https for server '${server.name}': $url" }
            return null
        }

        return try {
            val transport = HttpClientSseClientTransport.builder(parsed.toString())
                .customizeClient { it.connectTimeout(Duration.ofMillis(connectionTimeoutMs)) }
                .build()

            val client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(connectionTimeoutMs))
                .initializationTimeout(Duration.ofMillis(connectionTimeoutMs))
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
     * Note: Streamable HTTP transport is not available in MCP SDK 0.17.2.
     * Use SSE transport as an alternative. This will be implemented when
     * the SDK is upgraded to a version that includes HttpClientStreamableHttpTransport.
     */
    private fun connectHttp(server: McpServer): McpSyncClient? {
        logger.warn {
            "HTTP (Streamable) transport is not yet supported in MCP SDK 0.17.2. " +
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
                    mcpInputSchema = tool.inputSchema(),
                    maxOutputLength = securityConfig.maxToolOutputLength
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load tools from $serverName" }
            emptyList()
        }
    }

    override suspend fun disconnect(serverName: String) {
        mutexFor(serverName).withLock {
            disconnectInternal(serverName)
        }
    }

    /**
     * Internal disconnect logic (non-suspend, safe for use in close()).
     */
    private fun disconnectInternal(serverName: String) {
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

    override suspend fun ensureConnected(serverName: String): Boolean {
        val status = statuses[serverName]
        if (status == McpServerStatus.CONNECTED) return true
        if (status != McpServerStatus.FAILED && status != McpServerStatus.DISCONNECTED) return false
        if (!reconnectionProperties.enabled) return false

        logger.info { "On-demand reconnection attempt for MCP server: $serverName" }
        return connect(serverName)
    }

    /**
     * Schedule a background reconnection task with exponential backoff.
     * No-op if reconnection is disabled or a task is already running for this server.
     */
    private fun scheduleReconnection(serverName: String) {
        if (!reconnectionProperties.enabled) return
        if (!reconnectingServers.add(serverName)) return // already has a task

        reconnectScope.launch {
            val maxAttempts = reconnectionProperties.maxAttempts
            for (attempt in 1..maxAttempts) {
                val baseDelay = minOf(
                    (reconnectionProperties.initialDelayMs *
                        Math.pow(reconnectionProperties.multiplier, (attempt - 1).toDouble())).toLong(),
                    reconnectionProperties.maxDelayMs
                )
                // Add ±25% jitter
                val jitter = (baseDelay * 0.25 * (Math.random() * 2 - 1)).toLong()
                val delayMs = (baseDelay + jitter).coerceAtLeast(0)

                logger.info {
                    "MCP reconnection scheduled for '$serverName' " +
                        "(attempt $attempt/$maxAttempts, delay ${delayMs}ms)"
                }

                try {
                    delay(delayMs)
                } catch (e: Exception) {
                    reconnectingServers.remove(serverName)
                    e.throwIfCancellation()
                    throw e
                }

                // Check if server was unregistered, manually reconnected, or explicitly disconnected
                val currentStatus = statuses[serverName]
                if (!servers.containsKey(serverName) ||
                    currentStatus == McpServerStatus.CONNECTED ||
                    currentStatus == McpServerStatus.DISCONNECTED
                ) {
                    reconnectingServers.remove(serverName)
                    return@launch
                }

                val success = try {
                    connect(serverName)
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        reconnectingServers.remove(serverName)
                    }
                    e.throwIfCancellation()
                    logger.warn(e) { "Reconnection attempt $attempt/$maxAttempts failed for '$serverName'" }
                    false
                }

                if (success) {
                    logger.info { "MCP server '$serverName' reconnected on attempt $attempt" }
                    reconnectingServers.remove(serverName)
                    return@launch
                }
            }

            logger.warn { "MCP reconnection exhausted ($maxAttempts attempts) for '$serverName'" }
            reconnectingServers.remove(serverName)
        }
    }

    override fun getAllToolCallbacks(): List<ToolCallback> {
        return toolCallbacksCache.values.flatten()
    }

    override fun getToolCallbacks(serverName: String): List<ToolCallback> {
        return toolCallbacksCache[serverName] ?: emptyList()
    }

    override fun listServers(): List<McpServer> {
        return store?.list() ?: servers.values.toList()
    }

    override fun getStatus(serverName: String): McpServerStatus? {
        return statuses[serverName]
    }

    /**
     * Close all connected MCP servers and release resources.
     * Uses non-suspend disconnectInternal() to avoid runBlocking deadlock risk.
     */
    override fun close() {
        logger.info { "Closing MCP Manager, disconnecting all servers" }
        reconnectScope.cancel()
        reconnectingServers.clear()
        for (serverName in clients.keys.toList()) {
            disconnectInternal(serverName)
        }
        servers.clear()
        statuses.clear()
        serverMutexes.clear()
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
    private val mcpInputSchema: McpSchema.JsonSchema?,
    private val maxOutputLength: Int = 50_000
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

            // Convert result to string and enforce output length limit
            val output = result.content().joinToString("\n") { content ->
                when (content) {
                    is McpSchema.TextContent -> content.text()
                    is McpSchema.ImageContent -> "[Image: ${content.mimeType()}]"
                    is McpSchema.EmbeddedResource -> "[Resource: ${content.resource().uri()}]"
                    else -> content.toString()
                }
            }

            if (output.length > maxOutputLength) {
                logger.warn { "MCP tool '$name' output truncated: ${output.length} -> $maxOutputLength chars" }
                output.take(maxOutputLength) + "\n[TRUNCATED: output exceeded $maxOutputLength characters]"
            } else {
                output
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to call MCP tool: $name" }
            "Error: ${e.message}"
        }
    }
}
