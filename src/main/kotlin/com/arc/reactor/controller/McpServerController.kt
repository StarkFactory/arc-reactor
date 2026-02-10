package com.arc.reactor.controller

import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange

private val logger = KotlinLogging.logger {}

/**
 * MCP Server Management API Controller
 *
 * Provides REST APIs for dynamic MCP server registration and management.
 * Admin-only for write operations when auth is enabled.
 *
 * ## Endpoints
 * - GET    /api/mcp/servers                    : List all servers with status
 * - POST   /api/mcp/servers                    : Register + connect
 * - GET    /api/mcp/servers/{name}             : Server details with tools
 * - PUT    /api/mcp/servers/{name}             : Update config
 * - DELETE /api/mcp/servers/{name}             : Disconnect + remove
 * - POST   /api/mcp/servers/{name}/connect     : Connect
 * - POST   /api/mcp/servers/{name}/disconnect  : Disconnect
 */
@Tag(name = "MCP Servers", description = "Dynamic MCP server management (ADMIN only for write operations)")
@RestController
@RequestMapping("/api/mcp/servers")
class McpServerController(
    private val mcpManager: McpManager,
    private val mcpServerStore: McpServerStore
) {

    /**
     * List all registered MCP servers with connection status.
     */
    @GetMapping
    fun listServers(): List<McpServerResponse> {
        return mcpManager.listServers().map { it.toResponse() }
    }

    /**
     * Register a new MCP server and optionally connect.
     */
    @PostMapping
    suspend fun registerServer(
        @Valid @RequestBody request: RegisterMcpServerRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val existing = mcpServerStore.findByName(request.name)
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to "MCP server '${request.name}' already exists"))
        }

        val server = request.toMcpServer()
        mcpManager.register(server)

        if (request.autoConnect) {
            try {
                mcpManager.connect(server.name)
            } catch (e: Exception) {
                logger.warn(e) { "Auto-connect failed for '${server.name}'" }
            }
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(server.toResponse())
    }

    /**
     * Get server details including connection status and tool list.
     */
    @GetMapping("/{name}")
    fun getServer(@PathVariable name: String): ResponseEntity<Any> {
        val server = mcpServerStore.findByName(name)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "MCP server '$name' not found"))

        val status = mcpManager.getStatus(name) ?: McpServerStatus.PENDING
        val tools = mcpManager.getToolCallbacks(name).map { it.name }

        return ResponseEntity.ok(McpServerDetailResponse(
            id = server.id,
            name = server.name,
            description = server.description,
            transportType = server.transportType.name,
            config = server.config,
            version = server.version,
            autoConnect = server.autoConnect,
            status = status.name,
            tools = tools,
            createdAt = server.createdAt.toEpochMilli(),
            updatedAt = server.updatedAt.toEpochMilli()
        ))
    }

    /**
     * Update MCP server configuration.
     * Requires reconnection to apply transport changes.
     */
    @PutMapping("/{name}")
    fun updateServer(
        @PathVariable name: String,
        @Valid @RequestBody request: UpdateMcpServerRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val updateData = McpServer(
            name = name,
            description = request.description,
            transportType = request.transportType?.let { McpTransportType.valueOf(it) }
                ?: McpTransportType.SSE,
            config = request.config ?: emptyMap(),
            version = request.version,
            autoConnect = request.autoConnect ?: false
        )

        val updated = mcpServerStore.update(name, updateData)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "MCP server '$name' not found"))

        return ResponseEntity.ok(updated.toResponse())
    }

    /**
     * Disconnect and remove an MCP server.
     */
    @DeleteMapping("/{name}")
    suspend fun deleteServer(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        mcpServerStore.findByName(name)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "MCP server '$name' not found"))

        mcpManager.unregister(name)

        return ResponseEntity.noContent().build()
    }

    /**
     * Connect to a registered MCP server.
     */
    @PostMapping("/{name}/connect")
    suspend fun connectServer(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        mcpServerStore.findByName(name)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "MCP server '$name' not found"))

        val success = mcpManager.connect(name)
        val status = mcpManager.getStatus(name) ?: McpServerStatus.FAILED

        return if (success) {
            val tools = mcpManager.getToolCallbacks(name).map { it.name }
            ResponseEntity.ok(mapOf("status" to status.name, "tools" to tools))
        } else {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("status" to status.name, "error" to "Failed to connect"))
        }
    }

    /**
     * Disconnect from an MCP server (without removing).
     */
    @PostMapping("/{name}/disconnect")
    suspend fun disconnectServer(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        mcpServerStore.findByName(name)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "MCP server '$name' not found"))

        mcpManager.disconnect(name)
        val status = mcpManager.getStatus(name) ?: McpServerStatus.DISCONNECTED

        return ResponseEntity.ok(mapOf("status" to status.name))
    }

    // ---- DTOs ----

    private fun McpServer.toResponse() = McpServerResponse(
        id = id,
        name = name,
        description = description,
        transportType = transportType.name,
        autoConnect = autoConnect,
        status = (mcpManager.getStatus(name) ?: McpServerStatus.PENDING).name,
        toolCount = mcpManager.getToolCallbacks(name).size,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli()
    )
}

data class RegisterMcpServerRequest(
    @field:NotBlank(message = "Server name is required")
    @field:Size(max = 100, message = "Server name must not exceed 100 characters")
    val name: String,
    @field:Size(max = 500, message = "Description must not exceed 500 characters")
    val description: String? = null,
    val transportType: String = "SSE",
    @field:Size(max = 20, message = "Config must not exceed 20 entries")
    val config: Map<String, Any> = emptyMap(),
    val version: String? = null,
    val autoConnect: Boolean = true
) {
    fun toMcpServer() = McpServer(
        name = name,
        description = description,
        transportType = McpTransportType.valueOf(transportType),
        config = config,
        version = version,
        autoConnect = autoConnect
    )
}

data class UpdateMcpServerRequest(
    val description: String? = null,
    val transportType: String? = null,
    val config: Map<String, Any>? = null,
    val version: String? = null,
    val autoConnect: Boolean? = null
)

data class McpServerResponse(
    val id: String,
    val name: String,
    val description: String?,
    val transportType: String,
    val autoConnect: Boolean,
    val status: String,
    val toolCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class McpServerDetailResponse(
    val id: String,
    val name: String,
    val description: String?,
    val transportType: String,
    val config: Map<String, Any>,
    val version: String?,
    val autoConnect: Boolean,
    val status: String,
    val tools: List<String>,
    val createdAt: Long,
    val updatedAt: Long
)
