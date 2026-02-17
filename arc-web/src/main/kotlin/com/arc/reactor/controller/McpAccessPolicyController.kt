package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.mcp.McpServerStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ServerWebExchange
import java.net.URI
import java.time.Instant

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
            detail = "status=${response.statusCode.value()}, jiraProjects=${request.allowedJiraProjectKeys.size}, confluenceSpaces=${request.allowedConfluenceSpaceKeys.size}"
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
        val baseUrl = resolveAdminBaseUrl(config)
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    ErrorResponse(
                        error = "MCP server '$name' has no admin URL. Set config.adminUrl or config.url(/sse)",
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

        return try {
            val client = WebClient.builder().baseUrl(baseUrl).build()
            val responseBody = when (method) {
                HttpMethod.GET -> client.get()
                    .uri("/admin/access-policy")
                    .header("X-Admin-Token", token)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .awaitSingleOrNull()
                HttpMethod.PUT -> client.put()
                    .uri("/admin/access-policy")
                    .header("X-Admin-Token", token)
                    .bodyValue(body ?: emptyMap<String, Any>())
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .awaitSingleOrNull()
                HttpMethod.DELETE -> client.delete()
                    .uri("/admin/access-policy")
                    .header("X-Admin-Token", token)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .awaitSingleOrNull()
            }

            ResponseEntity.ok(parseJsonOrString(responseBody ?: ""))
        } catch (e: WebClientResponseException) {
            val payload = parseJsonOrString(e.responseBodyAsString)
            ResponseEntity.status(e.statusCode).body(payload)
        } catch (e: Exception) {
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

    private fun resolveAdminBaseUrl(config: Map<String, Any>): String? {
        val explicit = config["adminUrl"]?.toString()?.trim().orEmpty()
        if (explicit.isNotBlank()) return explicit.trimEnd('/')

        val sseUrl = config["url"]?.toString()?.trim().orEmpty()
        if (sseUrl.isBlank()) return null

        return try {
            val uri = URI(sseUrl)
            val path = uri.path ?: ""
            val basePath = if (path.endsWith("/sse")) path.removeSuffix("/sse") else path
            val rebuilt = URI(uri.scheme, uri.userInfo, uri.host, uri.port, basePath, null, null)
            rebuilt.toString().trimEnd('/')
        } catch (_: Exception) {
            null
        }
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
        private val objectMapper = jacksonObjectMapper()
    }
}

data class UpdateMcpAccessPolicyRequest(
    val allowedJiraProjectKeys: List<String> = emptyList(),
    val allowedConfluenceSpaceKeys: List<String> = emptyList()
)
