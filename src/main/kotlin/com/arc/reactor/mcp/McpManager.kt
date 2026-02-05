package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.tool.ToolCallback
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * MCP (Model Context Protocol) 서버 관리자
 *
 * 동적 Tool 로딩을 위한 MCP 서버 연결 관리.
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
     */
    fun getAllToolCallbacks(): List<ToolCallback>

    /**
     * 특정 서버의 Tool Callback 조회
     */
    fun getToolCallbacks(serverName: String): List<ToolCallback>

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
 * 기본 MCP Manager 구현
 *
 * Spring AI MCP 클라이언트 사용.
 */
class DefaultMcpManager : McpManager {

    private val servers = mutableMapOf<String, McpServer>()
    private val toolCallbacks = mutableMapOf<String, List<ToolCallback>>()
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

            // TODO: Spring AI MCP 클라이언트로 실제 연결
            // val mcpClient = McpClient.builder()
            //     .transport(server.transport)
            //     .build()
            // toolCallbacks[serverName] = mcpClient.getToolCallbacks()

            statuses[serverName] = McpServerStatus.CONNECTED
            logger.info { "MCP server connected: $serverName" }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to connect MCP server: $serverName" }
            statuses[serverName] = McpServerStatus.FAILED
            false
        }
    }

    override suspend fun disconnect(serverName: String) {
        logger.info { "Disconnecting MCP server: $serverName" }
        toolCallbacks.remove(serverName)
        statuses[serverName] = McpServerStatus.DISCONNECTED
    }

    override fun getAllToolCallbacks(): List<ToolCallback> {
        return toolCallbacks.values.flatten()
    }

    override fun getToolCallbacks(serverName: String): List<ToolCallback> {
        return toolCallbacks[serverName] ?: emptyList()
    }

    override fun listServers(): List<McpServer> {
        return servers.values.toList()
    }

    override fun getStatus(serverName: String): McpServerStatus? {
        return statuses[serverName]
    }
}
