package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.tool.ToolCallback
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import mu.KotlinLogging
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

private val logger = KotlinLogging.logger {}

internal data class McpConnectionHandle(
    val client: McpSyncClient,
    val tools: List<ToolCallback>
)

/**
 * MCP transport and tool-discovery support.
 */
internal class McpConnectionSupport(
    private val connectionTimeoutMs: Long,
    private val maxToolOutputLength: Int
) {

    fun open(server: McpServer): McpConnectionHandle? {
        val client = when (server.transportType) {
            McpTransportType.STDIO -> connectStdio(server)
            McpTransportType.SSE -> connectSse(server)
            McpTransportType.HTTP -> connectHttp(server)
        } ?: return null

        val tools = loadToolCallbacks(client, server.name)
        return McpConnectionHandle(client = client, tools = tools)
    }

    fun close(serverName: String, client: McpSyncClient) {
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

            client.initialize()
            client
        } catch (e: Exception) {
            logger.error(e) { "Failed to create STDIO transport for ${server.name}" }
            null
        }
    }

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
     * Streamable HTTP transport is not available in MCP SDK 0.17.2.
     */
    private fun connectHttp(server: McpServer): McpSyncClient? {
        logger.warn {
            "HTTP (Streamable) transport is not yet supported in MCP SDK 0.17.2. " +
                "Use SSE transport instead for server: ${server.name}"
        }
        return null
    }

    private fun loadToolCallbacks(client: McpSyncClient, serverName: String): List<ToolCallback> {
        return try {
            val toolsResult = client.listTools()
            val tools = toolsResult.tools()
            logger.info { "Loaded ${tools.size} tools from $serverName" }

            tools.map { tool ->
                McpToolCallback(
                    client = client,
                    name = tool.name(),
                    description = tool.description() ?: "",
                    mcpInputSchema = tool.inputSchema(),
                    maxOutputLength = maxToolOutputLength
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load tools from $serverName" }
            emptyList()
        }
    }
}
