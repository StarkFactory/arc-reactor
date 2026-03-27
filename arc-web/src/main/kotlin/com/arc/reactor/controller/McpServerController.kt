package com.arc.reactor.controller

import com.arc.reactor.audit.recordAdminAudit
import com.arc.reactor.agent.config.AgentProperties
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * MCP 서버 관리 API 컨트롤러.
 *
 * 동적 MCP 서버 등록 및 관리를 위한 REST API를 제공합니다.
 * 모든 쓰기 작업은 ADMIN 권한이 필요합니다.
 *
 * ## 엔드포인트
 * - GET    /api/mcp/servers                    : 전체 서버 목록 및 상태 조회
 * - POST   /api/mcp/servers                    : 서버 등록 + 자동 연결
 * - GET    /api/mcp/servers/{name}             : 서버 상세 정보 및 도구 목록 조회
 * - PUT    /api/mcp/servers/{name}             : 서버 설정 수정
 * - DELETE /api/mcp/servers/{name}             : 연결 해제 + 서버 제거
 * - POST   /api/mcp/servers/{name}/connect     : 서버 연결
 * - POST   /api/mcp/servers/{name}/disconnect  : 서버 연결 해제
 *
 * @see McpManager
 * @see McpServerStore
 */
@Tag(name = "MCP Servers", description = "Dynamic MCP server management (ADMIN only for write operations)")
@RestController
@RequestMapping("/api/mcp/servers")
class McpServerController(
    private val mcpManager: McpManager,
    private val mcpServerStore: McpServerStore,
    private val adminAuditStore: AdminAuditStore,
    private val agentProperties: AgentProperties
) {

    /** 등록된 모든 MCP 서버와 연결 상태를 조회한다. */
    @Operation(summary = "등록된 MCP 서버 전체 목록 조회")
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
     * 새 MCP 서버를 등록하고 선택적으로 연결한다.
     *
     * WHY: 보안 허용 목록에 없는 서버 등록은 거부한다. HTTP 전송 방식은
     * MCP SDK 0.17.2에서 미지원이므로 SSE 또는 STDIO만 허용한다.
     */
    @Operation(summary = "MCP 서버 등록 (관리자)")
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
            return conflictResponse("MCP server '${request.name}' already exists")
        }

        val transportType = parseTransportType(request.transportType)
            ?: return badRequestResponse("Invalid transportType: ${request.transportType}")
        if (transportType == McpTransportType.HTTP) {
            return badRequestResponse(HTTP_TRANSPORT_NOT_SUPPORTED)
        }

        if (transportType == McpTransportType.SSE) {
            val urlError = validateSseUrl(request.config)
            if (urlError != null) return badRequestResponse(urlError)
            val adminUrlError = validateAdminUrl(request.config)
            if (adminUrlError != null) return badRequestResponse(adminUrlError)
        }

        val server = request.toMcpServer(transportType)
        mcpManager.register(server)
        val registeredInRuntime = mcpManager.getStatus(server.name) != null ||
            mcpManager.listServers().any { it.name == server.name }
        if (!registeredInRuntime) {
            return badRequestResponse("MCP server '${server.name}' is not allowed by the security allowlist.")
        }
        persistServerIfMissing(server)

        if (request.autoConnect) {
            try {
                withContext(Dispatchers.IO) {
                    mcpManager.connect(server.name)
                }
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

    /** 연결 상태와 도구 목록을 포함한 서버 상세 정보를 조회한다. */
    @Operation(summary = "MCP 서버 상세 정보 및 도구 목록 조회")
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
            ?: return notFoundResponse("MCP server '$name' not found")

        val status = mcpManager.getStatus(name) ?: McpServerStatus.PENDING
        val tools = mcpManager.getToolCallbacks(name).map { it.name }

        return ResponseEntity.ok(McpServerDetailResponse(
            id = server.id,
            name = server.name,
            description = server.description,
            transportType = server.transportType.name,
            config = sanitizeConfig(server.config),
            version = server.version,
            autoConnect = server.autoConnect,
            status = status.name,
            tools = tools,
            createdAt = server.createdAt.toEpochMilli(),
            updatedAt = server.updatedAt.toEpochMilli()
        ))
    }

    /**
     * MCP 서버 설정을 수정한다.
     * 전송 방식 또는 연결 설정이 변경되면 자동으로 재연결한다.
     */
    @Operation(summary = "MCP 서버 설정 수정 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "MCP server updated"),
        ApiResponse(responseCode = "400", description = "Invalid transport type"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found")
    ])
    @PutMapping("/{name}")
    suspend fun updateServer(
        @PathVariable name: String,
        @Valid @RequestBody request: UpdateMcpServerRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val existing = mcpServerStore.findByName(name)
            ?: return notFoundResponse("MCP server '$name' not found")

        val transportType = when (val requested = request.transportType) {
            null -> existing.transportType
            else -> parseTransportType(requested)
                ?: return badRequestResponse("Invalid transportType: $requested")
        }
        if (transportType == McpTransportType.HTTP) {
            return badRequestResponse(HTTP_TRANSPORT_NOT_SUPPORTED)
        }

        val effectiveConfig = request.config ?: existing.config
        if (transportType == McpTransportType.SSE) {
            val urlError = validateSseUrl(effectiveConfig)
            if (urlError != null) return badRequestResponse(urlError)
        }

        val updateData = McpServer(
            name = name,
            description = request.description ?: existing.description,
            transportType = transportType,
            config = effectiveConfig,
            version = request.version ?: existing.version,
            autoConnect = request.autoConnect ?: existing.autoConnect
        )

        val statusBeforeUpdate = mcpManager.getStatus(name)
        val updated = mcpServerStore.update(name, updateData)
            ?: return notFoundResponse("MCP server '$name' not found")
        mcpManager.syncRuntimeServer(updated)
        applyRuntimeUpdate(existing, updated, statusBeforeUpdate)

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
     * 설정 변경 후 런타임 상태를 반영한다.
     * WHY: transport/config/version이 변경되면 재연결 필요. autoConnect가 새로 활성화되면 연결 시도.
     */
    private suspend fun applyRuntimeUpdate(
        existing: McpServer,
        updated: McpServer,
        previousStatus: McpServerStatus?
    ) {
        val reconnectRequired = previousStatus == McpServerStatus.CONNECTED && requiresReconnect(existing, updated)
        val connectRequired = !reconnectRequired &&
            updated.autoConnect &&
            previousStatus != McpServerStatus.CONNECTED &&
            previousStatus != McpServerStatus.CONNECTING

        when {
            reconnectRequired -> withContext(Dispatchers.IO) {
                logger.info { "Reconnecting MCP server '${updated.name}' after configuration update" }
                mcpManager.disconnect(updated.name)
                val connected = mcpManager.connect(updated.name)
                if (!connected) {
                    logger.warn { "MCP server '${updated.name}' failed to reconnect after update" }
                }
            }

            connectRequired -> withContext(Dispatchers.IO) {
                logger.info { "Connecting MCP server '${updated.name}' after enabling autoConnect or updating config" }
                val connected = mcpManager.connect(updated.name)
                if (!connected) {
                    logger.warn { "MCP server '${updated.name}' failed to connect after update" }
                }
            }
        }
    }

    /** 전송 방식, config, 또는 버전이 변경되었으면 재연결이 필요한지 판단한다. */
    private fun requiresReconnect(existing: McpServer, updated: McpServer): Boolean {
        return existing.transportType != updated.transportType ||
            existing.config != updated.config ||
            existing.version != updated.version
    }

    /** MCP 서버 연결을 해제하고 제거한다. */
    @Operation(summary = "MCP 서버 연결 해제 및 제거 (관리자)")
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
            ?: return notFoundResponse("MCP server '$name' not found")

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

    /** 등록된 MCP 서버에 연결한다. 연결 성공 시 사용 가능한 도구 목록을 반환한다. */
    @Operation(summary = "등록된 MCP 서버에 연결 (관리자)")
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
            ?: return notFoundResponse("MCP server '$name' not found")

        val success = withContext(Dispatchers.IO) {
            mcpManager.connect(name)
        }
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

    /** MCP 서버 연결을 해제한다 (서버 등록은 유지). */
    @Operation(summary = "MCP 서버 연결 해제 (관리자)")
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
            ?: return notFoundResponse("MCP server '$name' not found")

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

    /** 전송 방식 문자열을 [McpTransportType] enum으로 파싱한다. 대소문자 무시. */
    private fun parseTransportType(raw: String): McpTransportType? {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return null
        return McpTransportType.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
    }

    /** SSE 전송 방식의 URL을 검증한다. SSRF 방지를 위해 URL 유효성을 확인한다. */
    private fun validateSseUrl(config: Map<String, Any>): String? {
        val url = config["url"]?.toString()
            ?: return "SSE transport requires a 'url' in config"
        return SsrfUrlValidator.validate(url, agentProperties.mcp.allowPrivateAddresses)
    }

    /** admin URL이 제공된 경우 SSRF 검증을 수행한다. */
    private fun validateAdminUrl(config: Map<String, Any>): String? {
        val adminUrl = config["adminUrl"]?.toString() ?: return null
        if (adminUrl.isBlank()) return null
        return SsrfUrlValidator.validate(adminUrl, agentProperties.mcp.allowPrivateAddresses)
    }

    /** 저장소에 서버 정보가 없으면 영속화한다. 동시 저장 충돌은 무시한다. */
    private fun persistServerIfMissing(server: McpServer) {
        if (mcpServerStore.findByName(server.name) != null) return
        try {
            mcpServerStore.save(server)
        } catch (e: IllegalArgumentException) {
            logger.debug(e) { "MCP server '${server.name}' was concurrently saved by another request" }
        }
    }

    /** API 응답에서 민감한 설정 값(token, secret 등)을 마스킹한다. */
    private fun sanitizeConfig(config: Map<String, Any>): Map<String, Any?> {
        return sanitizeConfigMap(config)
    }

    private fun sanitizeConfigMap(config: Map<*, *>): Map<String, Any?> {
        return config.entries.associate { (rawKey, rawValue) ->
            val key = rawKey?.toString() ?: "unknown"
            key to sanitizeConfigValue(key, rawValue)
        }
    }

    private fun sanitizeConfigValue(key: String?, value: Any?): Any? {
        if (key != null && isSensitiveConfigKey(key)) {
            return MASKED_VALUE
        }
        return when (value) {
            is Map<*, *> -> sanitizeConfigMap(value)
            is List<*> -> value.map { item -> sanitizeConfigValue(null, item) }
            else -> value
        }
    }

    private fun isSensitiveConfigKey(key: String): Boolean {
        val normalized = key.lowercase()
        return SENSITIVE_KEY_MARKERS.any { normalized.contains(it) }
    }

    // ---- DTO 변환 ----

    /** [McpServer]를 응답 DTO로 변환한다. 런타임 상태 정보와 도구 개수를 포함한다. */
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

    companion object {
        private const val HTTP_TRANSPORT_NOT_SUPPORTED = "HTTP transport is not supported. Use SSE or STDIO."
        private const val MASKED_VALUE = "********"
        private val SENSITIVE_KEY_MARKERS = listOf(
            "token",
            "secret",
            "password",
            "authorization",
            "api_key",
            "apikey",
            "credential"
        )
    }
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
    val config: Map<String, Any?>,
    val version: String?,
    val autoConnect: Boolean,
    val status: String,
    val tools: List<String>,
    val createdAt: Long,
    val updatedAt: Long
)
