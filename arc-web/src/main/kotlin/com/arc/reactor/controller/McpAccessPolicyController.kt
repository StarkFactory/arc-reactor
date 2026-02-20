package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * Proxy controller for MCP-server-specific admin access-policy APIs.
 *
 * Intended for Atlassian MCP server allowlist control from Arc Reactor admin UI.
 */
@Tag(name = "MCP Access Policy", description = "Proxy access-policy management for MCP servers (ADMIN)")
@RestController
@RequestMapping("/api/mcp/servers/{name}/access-policy")
class McpAccessPolicyController(
    private val mcpServerStore: McpServerStore,
    private val adminAuditStore: AdminAuditStore
) {
    @Operation(summary = "Get access policy from MCP server admin API")
    @GetMapping
    suspend fun getPolicy(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val response = proxy(name, HttpMethod.GET, null)
        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_access_policy",
            action = "READ",
            actor = currentActor(exchange),
            resourceType = "mcp_server",
            resourceId = name,
            detail = "status=${response.statusCode.value()}"
        )
        return response
    }

    @Operation(summary = "Update access policy on MCP server admin API")
    @PutMapping
    suspend fun updatePolicy(
        @PathVariable name: String,
        @RequestBody request: UpdateMcpAccessPolicyRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val response = proxy(name, HttpMethod.PUT, request)
        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_access_policy",
            action = "UPDATE",
            actor = currentActor(exchange),
            resourceType = "mcp_server",
            resourceId = name,
            detail = "status=${response.statusCode.value()}, " +
                "jiraProjects=${request.allowedJiraProjectKeys.size}, " +
                "confluenceSpaces=${request.allowedConfluenceSpaceKeys.size}"
        )
        return response
    }

    @Operation(summary = "Clear dynamic policy on MCP server admin API")
    @DeleteMapping
    suspend fun clearPolicy(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val response = proxy(name, HttpMethod.DELETE, null)
        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_access_policy",
            action = "DELETE",
            actor = currentActor(exchange),
            resourceType = "mcp_server",
            resourceId = name,
            detail = "status=${response.statusCode.value()}, reset_to_env_defaults=true"
        )
        return response
    }

    private suspend fun proxy(
        name: String,
        method: HttpMethod,
        body: Any?
    ): ResponseEntity<Any> {
        val server = mcpServerStore.findByName(name)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse(error = "MCP server '$name' not found", timestamp = Instant.now().toString()))

        val config = server.config
        val baseUrl = McpAdminUrlResolver.resolve(config)
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    ErrorResponse(
                        error = "MCP server '$name' has invalid admin URL. " +
                            "Set absolute config.adminUrl or config.url(/sse) with http/https",
                        timestamp = Instant.now().toString()
                    )
                )
        val token = config["adminToken"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    ErrorResponse(
                        error = "MCP server '$name' has no admin token. Set config.adminToken",
                        timestamp = Instant.now().toString()
                    )
                )
        val timeoutMs = resolveAdminTimeoutMs(config)

        return try {
            val client = WebClient.builder().baseUrl(baseUrl).build()
            when (method) {
                HttpMethod.GET -> client.get()
                    .uri("/admin/access-policy")
                    .header("X-Admin-Token", token)
                    .exchangeToMono { response ->
                        response.bodyToMono(String::class.java)
                            .defaultIfEmpty("")
                            .map { responseBody -> toResponseEntity(response.statusCode(), responseBody) }
                    }
                    .timeout(Duration.ofMillis(timeoutMs))
                    .awaitSingleOrNull()
                HttpMethod.PUT -> client.put()
                    .uri("/admin/access-policy")
                    .header("X-Admin-Token", token)
                    .bodyValue(body ?: emptyMap<String, Any>())
                    .exchangeToMono { response ->
                        response.bodyToMono(String::class.java)
                            .defaultIfEmpty("")
                            .map { responseBody -> toResponseEntity(response.statusCode(), responseBody) }
                    }
                    .timeout(Duration.ofMillis(timeoutMs))
                    .awaitSingleOrNull()
                HttpMethod.DELETE -> client.delete()
                    .uri("/admin/access-policy")
                    .header("X-Admin-Token", token)
                    .exchangeToMono { response ->
                        response.bodyToMono(String::class.java)
                            .defaultIfEmpty("")
                            .map { responseBody -> toResponseEntity(response.statusCode(), responseBody) }
                    }
                    .timeout(Duration.ofMillis(timeoutMs))
                    .awaitSingleOrNull()
            }
                ?: ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                    ErrorResponse(
                        error = "MCP admin API returned no response",
                        timestamp = Instant.now().toString()
                    )
                )
        } catch (_: TimeoutException) {
            ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(
                ErrorResponse(
                    error = "MCP admin API timed out after ${timeoutMs}ms",
                    timestamp = Instant.now().toString()
                )
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to proxy access-policy request to MCP server '$name'" }
            ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(
                    ErrorResponse(
                        error = "Failed to call MCP admin API: ${e.message}",
                        timestamp = Instant.now().toString()
                    )
                )
        }
    }

    private fun toResponseEntity(statusCode: HttpStatusCode, body: String): ResponseEntity<Any> {
        if (statusCode == HttpStatus.NO_CONTENT) {
            return ResponseEntity.status(statusCode).build()
        }
        if (body.isBlank()) {
            return ResponseEntity.status(statusCode).build()
        }
        return ResponseEntity.status(statusCode).body(parseJsonOrString(body))
    }

    private fun resolveAdminTimeoutMs(config: Map<String, Any>): Long {
        val configured = when (val raw = config["adminTimeoutMs"]) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: DEFAULT_ADMIN_TIMEOUT_MS
        return configured.coerceIn(MIN_ADMIN_TIMEOUT_MS, MAX_ADMIN_TIMEOUT_MS)
    }

    private fun parseJsonOrString(body: String): Any {
        if (body.isBlank()) return emptyMap<String, Any>()
        return try {
            objectMapper.readValue(body, Any::class.java)
        } catch (_: Exception) {
            mapOf("raw" to body)
        }
    }

    private enum class HttpMethod { GET, PUT, DELETE }

    companion object {
        private const val DEFAULT_ADMIN_TIMEOUT_MS = 10_000L
        private const val MIN_ADMIN_TIMEOUT_MS = 100L
        private const val MAX_ADMIN_TIMEOUT_MS = 120_000L
        private val objectMapper = jacksonObjectMapper()
    }
}

data class UpdateMcpAccessPolicyRequest(
    val allowedJiraProjectKeys: List<String> = emptyList(),
    val allowedConfluenceSpaceKeys: List<String> = emptyList()
)
