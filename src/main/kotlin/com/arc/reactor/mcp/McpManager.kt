package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.tool.ToolCallback
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.spec.McpSchema
import mu.KotlinLogging

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
    fun getAllToolCallbacks(): List<Any>

    /**
     * Get tool callbacks from a specific MCP server.
     *
     * @param serverName Name of the server
     * @return List of tool callbacks, empty if not connected
     */
    fun getToolCallbacks(serverName: String): List<Any>

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
 * Supports STDIO transport fully, with SSE/HTTP as placeholders.
 *
 * ## Connection Flow
 * 1. Register server with [register]
 * 2. Connect with [connect] → initializes transport and loads tools
 * 3. Use tools via [getAllToolCallbacks] or [getToolCallbacks]
 * 4. Disconnect with [disconnect] when done
 *
 * ## Thread Safety
 * This implementation is NOT thread-safe. For concurrent access,
 * use external synchronization or a thread-safe wrapper.
 */
class DefaultMcpManager : McpManager {

    private val servers = mutableMapOf<String, McpServer>()
    private val clients = mutableMapOf<String, McpSyncClient>()
    private val toolCallbacksCache = mutableMapOf<String, List<ToolCallback>>()
    private val statuses = mutableMapOf<String, McpServerStatus>()

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

        return try {
            logger.info { "Connecting to MCP server: $serverName" }
            statuses[serverName] = McpServerStatus.CONNECTING

            val client = when (server.transportType) {
                McpTransportType.STDIO -> connectStdio(server)
                McpTransportType.SSE -> connectSse(server)
                McpTransportType.HTTP -> connectHttp(server)
            }

            if (client != null) {
                clients[serverName] = client

                // Tool Callbacks 로드
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

    /**
     * STDIO 트랜스포트로 연결
     */
    private fun connectStdio(server: McpServer): McpSyncClient? {
        val command = server.config["command"] as? String ?: return null
        val args = (server.config["args"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        return try {
            val params = ServerParameters.builder(command)
                .args(*args.toTypedArray())
                .build()

            val transport = StdioClientTransport(params)

            val client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation(server.name, server.version ?: "1.0.0"))
                .build()

            // 초기화
            client.initialize()

            client
        } catch (e: Exception) {
            logger.error(e) { "Failed to create STDIO transport for ${server.name}" }
            null
        }
    }

    /**
     * SSE 트랜스포트로 연결
     */
    private fun connectSse(server: McpServer): McpSyncClient? {
        val url = server.config["url"] as? String ?: return null

        return try {
            // SSE 트랜스포트는 HttpClientSseClientTransport 사용
            logger.info { "SSE transport configured for URL: $url" }
            // HttpClientSseClientTransport.builder(url).build()로 생성 가능
            // 하지만 현재는 STDIO만 완전 지원
            null
        } catch (e: Exception) {
            logger.error(e) { "Failed to create SSE transport for ${server.name}" }
            null
        }
    }

    /**
     * HTTP 트랜스포트로 연결
     */
    private fun connectHttp(server: McpServer): McpSyncClient? {
        val url = server.config["url"] as? String ?: return null

        return try {
            // HTTP 트랜스포트는 HttpClientStreamableHttpTransport 사용
            logger.info { "HTTP transport configured for URL: $url" }
            // HttpClientStreamableHttpTransport.builder(url).build()로 생성 가능
            null
        } catch (e: Exception) {
            logger.error(e) { "Failed to create HTTP transport for ${server.name}" }
            null
        }
    }

    /**
     * MCP 서버에서 Tool Callbacks 로드
     */
    private fun loadToolCallbacks(client: McpSyncClient, serverName: String): List<ToolCallback> {
        return try {
            val toolsResult = client.listTools()
            val tools = toolsResult.tools()
            logger.info { "Loaded ${tools.size} tools from $serverName" }

            // MCP Tool을 ToolCallback으로 변환
            tools.map { tool ->
                McpToolCallback(
                    client = client,
                    name = tool.name(),
                    description = tool.description() ?: "",
                    inputSchema = tool.inputSchema()
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load tools from $serverName" }
            emptyList()
        }
    }

    override suspend fun disconnect(serverName: String) {
        logger.info { "Disconnecting MCP server: $serverName" }

        clients[serverName]?.let { client ->
            try {
                client.closeGracefully()
            } catch (e: Exception) {
                logger.warn(e) { "Error during graceful shutdown of $serverName" }
            }
        }

        clients.remove(serverName)
        toolCallbacksCache.remove(serverName)
        statuses[serverName] = McpServerStatus.DISCONNECTED
    }

    override fun getAllToolCallbacks(): List<Any> {
        // 수동 연결된 MCP 서버의 Tools
        return toolCallbacksCache.values.flatten()
    }

    override fun getToolCallbacks(serverName: String): List<Any> {
        return toolCallbacksCache[serverName] ?: emptyList()
    }

    override fun listServers(): List<McpServer> {
        return servers.values.toList()
    }

    override fun getStatus(serverName: String): McpServerStatus? {
        return statuses[serverName]
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
    private val inputSchema: McpSchema.JsonSchema?
) : ToolCallback {

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        return try {
            val request = McpSchema.CallToolRequest(name, arguments)
            val result = client.callTool(request)

            // 결과를 문자열로 변환
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
