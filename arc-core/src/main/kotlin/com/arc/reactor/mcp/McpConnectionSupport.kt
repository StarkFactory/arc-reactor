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
    private val maxToolOutputLengthProvider: () -> Int,
    private val allowedStdioCommandsProvider: () -> Set<String> = {
        McpSecurityConfig.DEFAULT_ALLOWED_STDIO_COMMANDS
    },
    private val onConnectionError: (serverName: String) -> Unit = {}
) {

    companion object {
        /** Matches control characters below 0x20 except tab (0x09) and newline (0x0A). */
        private val UNSAFE_CONTROL_CHAR_REGEX = Regex("[\\x00-\\x08\\x0B-\\x1F]")
    }

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
        val args = (server.config["args"] as? List<*>)
            ?.filterIsInstance<String>() ?: emptyList()

        if (!validateStdioCommand(command, server.name)) return null
        if (!validateStdioArgs(args, server.name)) return null

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

    /**
     * Validates the STDIO command against the allowlist and rejects
     * path traversal patterns.
     */
    internal fun validateStdioCommand(
        command: String,
        serverName: String
    ): Boolean {
        if (command.contains("..")) {
            logger.warn {
                "STDIO command contains path traversal for server " +
                    "'$serverName': $command"
            }
            return false
        }

        val baseName = command.substringAfterLast("/")
        val allowed = allowedStdioCommandsProvider()
        if (baseName !in allowed) {
            logger.warn {
                "STDIO command '$baseName' is not in the allowed commands " +
                    "list for server '$serverName'. " +
                    "Allowed: $allowed"
            }
            return false
        }

        if (command.contains("/") && !Files.exists(Paths.get(command))) {
            logger.warn {
                "STDIO command does not exist for server " +
                    "'$serverName': $command"
            }
            return false
        }
        return true
    }

    /**
     * Validates STDIO args, rejecting null bytes and control characters.
     */
    internal fun validateStdioArgs(
        args: List<String>,
        serverName: String
    ): Boolean {
        for (arg in args) {
            if (UNSAFE_CONTROL_CHAR_REGEX.containsMatchIn(arg)) {
                logger.warn {
                    "STDIO args contain unsafe control characters " +
                        "for server '$serverName'"
                }
                return false
            }
        }
        return true
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
                    maxOutputLength = maxToolOutputLengthProvider(),
                    onConnectionError = { onConnectionError(serverName) }
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load tools from $serverName" }
            emptyList()
        }
    }
}
