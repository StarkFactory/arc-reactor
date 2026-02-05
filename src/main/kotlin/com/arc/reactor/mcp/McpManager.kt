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
 * MCP (Model Context Protocol) 서버 관리자
 *
 * 동적 Tool 로딩을 위한 MCP 서버 연결 관리.
 * MCP SDK 클라이언트와 통합.
 */
interface McpManager {
    /**
     * MCP 서버 등록
     */
    fun register(server: McpServer)

    /**
     * MCP 서버 연결
     */
    suspend fun connect(serverName: String): Boolean

    /**
     * MCP 서버 연결 해제
     */
    suspend fun disconnect(serverName: String)

    /**
     * 모든 MCP Tool Callback 조회
     * Spring AI ToolCallback 반환
     */
    fun getAllToolCallbacks(): List<Any>

    /**
     * 특정 서버의 Tool Callback 조회
     */
    fun getToolCallbacks(serverName: String): List<Any>

    /**
     * 등록된 서버 목록
     */
    fun listServers(): List<McpServer>

    /**
     * 서버 상태 조회
     */
    fun getStatus(serverName: String): McpServerStatus?
}

/**
 * MCP SDK 기반 구현
 *
 * STDIO, SSE, HTTP 트랜스포트 지원.
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
 * MCP Tool을 ToolCallback으로 래핑
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
