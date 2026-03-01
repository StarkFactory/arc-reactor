package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.support.throwIfCancellation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

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
    private val mcpServerStore: McpServerStore,
    private val adminAuditStore: AdminAuditStore
) {

    /**
     * List all registered MCP servers with connection status.
     */
    @Operation(summary = "List all registered MCP servers")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of MCP servers"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun listServers(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(mcpManager.listServers().map { it.toResponse() })
    }

    /**
     * Register a new MCP server and optionally connect.
     */
    @Operation(summary = "Register a new MCP server (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "MCP server registered"),
        ApiResponse(responseCode = "400", description = "Invalid request or transport type"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "409", description = "MCP server already exists")
    ])
    @PostMapping
    suspend fun registerServer(
        @Valid @RequestBody request: RegisterMcpServerRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val existing = mcpServerStore.findByName(request.name)
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                    ErrorResponse(
                        error = "MCP server '${request.name}' already exists",
                        timestamp = Instant.now().toString()
                    )
                )
        }

        val transportType = parseTransportType(request.transportType)
            ?: return badRequest("Invalid transportType: ${request.transportType}")
        if (transportType == McpTransportType.HTTP) {
            return badRequest("HTTP transport is not supported. Use SSE or STDIO.")
        }

        val server = request.toMcpServer(transportType)
        mcpManager.register(server)
        val registeredInRuntime = mcpManager.getStatus(server.name) != null ||
            mcpManager.listServers().any { it.name == server.name }
        if (!registeredInRuntime) {
            return badRequest("MCP server '${server.name}' is not allowed by the security allowlist.")
        }
        persistServerIfMissing(server)

        if (request.autoConnect) {
            try {
                mcpManager.connect(server.name)
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "Auto-connect failed for '${server.name}'" }
            }
        }

        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_server",
            action = "CREATE",
            actor = currentActor(exchange),
            resourceType = "mcp_server",
            resourceId = server.name,
            detail = "transport=${server.transportType}, autoConnect=${server.autoConnect}"
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(server.toResponse())
    }

    /**
     * Get server details including connection status and tool list.
     */
    @Operation(summary = "Get server details with tools")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "MCP server details"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found")
    ])
    @GetMapping("/{name}")
    fun getServer(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val server = mcpServerStore.findByName(name)
            ?: return mcpNotFound(name)

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
    @Operation(summary = "Update MCP server configuration (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "MCP server updated"),
        ApiResponse(responseCode = "400", description = "Invalid transport type"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found")
    ])
    @PutMapping("/{name}")
    fun updateServer(
        @PathVariable name: String,
        @Valid @RequestBody request: UpdateMcpServerRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val existing = mcpServerStore.findByName(name)
            ?: return mcpNotFound(name)

        val transportType = when (val requested = request.transportType) {
            null -> existing.transportType
            else -> parseTransportType(requested)
                ?: return badRequest("Invalid transportType: $requested")
        }
        if (transportType == McpTransportType.HTTP) {
            return badRequest("HTTP transport is not supported. Use SSE or STDIO.")
        }

        val updateData = McpServer(
            name = name,
            description = request.description ?: existing.description,
            transportType = transportType,
            config = request.config ?: existing.config,
            version = request.version ?: existing.version,
            autoConnect = request.autoConnect ?: existing.autoConnect
        )

        val updated = mcpServerStore.update(name, updateData)
            ?: return mcpNotFound(name)
        mcpManager.syncRuntimeServer(updated)

        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_server",
            action = "UPDATE",
            actor = currentActor(exchange),
            resourceType = "mcp_server",
            resourceId = name,
            detail = "transport=${updated.transportType}, autoConnect=${updated.autoConnect}"
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    /**
     * Disconnect and remove an MCP server.
     */
    @Operation(summary = "Disconnect and remove an MCP server (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "MCP server removed"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found")
    ])
    @DeleteMapping("/{name}")
    suspend fun deleteServer(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        mcpServerStore.findByName(name)
            ?: return mcpNotFound(name)

        mcpManager.unregister(name)

        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_server",
            action = "DELETE",
            actor = currentActor(exchange),
            resourceType = "mcp_server",
            resourceId = name
        )
        return ResponseEntity.noContent().build()
    }

    /**
     * Connect to a registered MCP server.
     */
    @Operation(summary = "Connect to a registered MCP server (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Connected to MCP server"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found"),
        ApiResponse(responseCode = "503", description = "Failed to connect to MCP server")
    ])
    @PostMapping("/{name}/connect")
    suspend fun connectServer(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        mcpServerStore.findByName(name)
            ?: return mcpNotFound(name)

        val success = mcpManager.connect(name)
        val status = mcpManager.getStatus(name) ?: McpServerStatus.FAILED

        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_server",
            action = "CONNECT",
            actor = currentActor(exchange),
            resourceType = "mcp_server",
            resourceId = name,
            detail = "success=$success, status=$status"
        )
        return if (success) {
            val tools = mcpManager.getToolCallbacks(name).map { it.name }
            ResponseEntity.ok(McpConnectResponse(status = status.name, tools = tools))
        } else {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse(error = "Failed to connect to '$name'", timestamp = Instant.now().toString()))
        }
    }

    /**
     * Disconnect from an MCP server (without removing).
     */
    @Operation(summary = "Disconnect from an MCP server (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Disconnected from MCP server"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found")
    ])
    @PostMapping("/{name}/disconnect")
    suspend fun disconnectServer(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        mcpServerStore.findByName(name)
            ?: return mcpNotFound(name)

        mcpManager.disconnect(name)
        val status = mcpManager.getStatus(name) ?: McpServerStatus.DISCONNECTED

        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_server",
            action = "DISCONNECT",
            actor = currentActor(exchange),
            resourceType = "mcp_server",
            resourceId = name,
            detail = "status=$status"
        )
        return ResponseEntity.ok(McpStatusResponse(status = status.name))
    }

    private fun mcpNotFound(name: String): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(error = "MCP server '$name' not found", timestamp = Instant.now().toString()))
    }

    private fun parseTransportType(raw: String): McpTransportType? {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return null
        return McpTransportType.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
    }

    private fun badRequest(message: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(
            ErrorResponse(error = message, timestamp = Instant.now().toString())
        )

    private fun persistServerIfMissing(server: McpServer) {
        if (mcpServerStore.findByName(server.name) != null) return
        try {
            mcpServerStore.save(server)
        } catch (e: IllegalArgumentException) {
            logger.debug(e) { "MCP server '${server.name}' was concurrently saved by another request" }
        }
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
    fun toMcpServer(transportType: McpTransportType) = McpServer(
        name = name,
        description = description,
        transportType = transportType,
        config = config,
        version = version,
        autoConnect = autoConnect
    )
}

data class UpdateMcpServerRequest(
    @field:Size(max = 500, message = "Description must not exceed 500 characters")
    val description: String? = null,
    val transportType: String? = null,
    @field:Size(max = 20, message = "Config must not exceed 20 entries")
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

data class McpConnectResponse(
    val status: String,
    val tools: List<String>
)

data class McpStatusResponse(
    val status: String
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
