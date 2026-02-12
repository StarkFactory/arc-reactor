package com.arc.reactor.mcp.model

import java.time.Instant
import java.util.UUID

/**
 * MCP server configuration
 */
data class McpServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val transportType: McpTransportType,
    val config: Map<String, Any> = emptyMap(),
    val version: String? = null,
    val autoConnect: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * MCP transport type
 */
enum class McpTransportType {
    /** Standard I/O (local process) */
    STDIO,

    /** Server-Sent Events */
    SSE,

    /** HTTP REST */
    HTTP
}

/**
 * MCP server status
 */
enum class McpServerStatus {
    /** Registered, not yet connected */
    PENDING,

    /** Connecting */
    CONNECTING,

    /** Connected */
    CONNECTED,

    /** Disconnected */
    DISCONNECTED,

    /** Connection failed */
    FAILED,

    /** Disabled */
    DISABLED
}
