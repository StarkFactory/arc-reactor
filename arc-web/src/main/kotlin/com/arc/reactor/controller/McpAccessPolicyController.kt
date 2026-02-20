package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
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
import java.util.concurrent.TimeUnit
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
    private val adminAuditStore: AdminAuditStore,
    private val meterRegistry: MeterRegistry? = null,
    private val adminWebClientFactory: McpAdminWebClientFactory = McpAdminWebClientFactory()
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
        val validationError = validatePolicyRequest(request)
        if (validationError != null) {
            val response = errorResponse(HttpStatus.BAD_REQUEST, validationError)
            recordAdminAudit(
                store = adminAuditStore,
                category = "mcp_access_policy",
                action = "UPDATE",
                actor = currentActor(exchange),
                resourceType = "mcp_server",
                resourceId = name,
                detail = "status=${response.statusCode.value()}, validationError=$validationError, " +
                    "jiraProjects=${request.allowedJiraProjectKeys.size}, " +
                    "confluenceSpaces=${request.allowedConfluenceSpaceKeys.size}"
            )
            return response
        }
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
        val startedAtNanos = System.nanoTime()
        val server = mcpServerStore.findByName(name)
            ?: return errorResponse(
                status = HttpStatus.NOT_FOUND,
                message = "MCP server '$name' not found"
            ).also { observeProxyCall(name, method, it.statusCode.value(), startedAtNanos) }

        val config = server.config
        val baseUrl = McpAdminUrlResolver.resolve(config)
            ?: return errorResponse(
                status = HttpStatus.BAD_REQUEST,
                message = "MCP server '$name' has invalid admin URL. " +
                    "Set absolute config.adminUrl or config.url(/sse) with http/https"
            )
                .also { observeProxyCall(name, method, it.statusCode.value(), startedAtNanos) }
        val token = config["adminToken"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return errorResponse(
                status = HttpStatus.BAD_REQUEST,
                message = "MCP server '$name' has no admin token. Set config.adminToken"
            )
                .also { observeProxyCall(name, method, it.statusCode.value(), startedAtNanos) }
        val timeoutMs = resolveAdminTimeoutMs(config)
        val connectTimeoutMs = resolveAdminConnectTimeoutMs(config, timeoutMs)

        val response: ResponseEntity<Any> = try {
            val client = adminWebClientFactory.getClient(baseUrl, connectTimeoutMs, timeoutMs)
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
                ?: errorResponse(
                    status = HttpStatus.BAD_GATEWAY,
                    message = "MCP admin API returned no response"
                )
        } catch (_: TimeoutException) {
            errorResponse(
                status = HttpStatus.GATEWAY_TIMEOUT,
                message = "MCP admin API timed out after ${timeoutMs}ms"
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to proxy access-policy request to MCP server '$name'" }
            errorResponse(
                status = HttpStatus.BAD_GATEWAY,
                message = "Failed to call MCP admin API: ${e.message}"
            )
        }
        observeProxyCall(name, method, response.statusCode.value(), startedAtNanos)
        return response
    }

    private fun validatePolicyRequest(request: UpdateMcpAccessPolicyRequest): String? {
        validatePolicyKeys(
            fieldName = "allowedJiraProjectKeys",
            keys = request.allowedJiraProjectKeys,
            maxItems = MAX_PROJECT_KEYS,
            maxLength = MAX_JIRA_KEY_LENGTH,
            pattern = JIRA_PROJECT_KEY_PATTERN
        )?.let { return it }
        validatePolicyKeys(
            fieldName = "allowedConfluenceSpaceKeys",
            keys = request.allowedConfluenceSpaceKeys,
            maxItems = MAX_SPACE_KEYS,
            maxLength = MAX_SPACE_KEY_LENGTH,
            pattern = CONFLUENCE_SPACE_KEY_PATTERN
        )?.let { return it }
        return null
    }

    private fun validatePolicyKeys(
        fieldName: String,
        keys: List<String>,
        maxItems: Int,
        maxLength: Int,
        pattern: Regex
    ): String? {
        if (keys.size > maxItems) {
            return "$fieldName must not exceed $maxItems items"
        }

        for ((index, rawKey) in keys.withIndex()) {
            val key = rawKey.trim()
            if (key.isEmpty()) {
                return "$fieldName[$index] must not be blank"
            }
            if (rawKey != key) {
                return "$fieldName[$index] must not contain leading or trailing whitespace"
            }
            if (key.length > maxLength) {
                return "$fieldName[$index] must not exceed $maxLength characters"
            }
            if (!pattern.matches(key)) {
                return "$fieldName[$index] has invalid format"
            }
        }
        return null
    }

    private fun toResponseEntity(statusCode: HttpStatusCode, body: String): ResponseEntity<Any> {
        if (statusCode == HttpStatus.NO_CONTENT) {
            return noBodyResponse(statusCode)
        }
        if (body.isBlank()) {
            return noBodyResponse(statusCode)
        }
        return bodyResponse(statusCode, parseJsonOrString(body))
    }

    private fun resolveAdminTimeoutMs(config: Map<String, Any>): Long {
        val configured = when (val raw = config["adminTimeoutMs"]) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: DEFAULT_ADMIN_TIMEOUT_MS
        return configured.coerceIn(MIN_ADMIN_TIMEOUT_MS, MAX_ADMIN_TIMEOUT_MS)
    }

    private fun resolveAdminConnectTimeoutMs(config: Map<String, Any>, requestTimeoutMs: Long): Int {
        val configured = when (val raw = config["adminConnectTimeoutMs"]) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: DEFAULT_ADMIN_CONNECT_TIMEOUT_MS

        val upperBound = minOf(requestTimeoutMs, MAX_ADMIN_CONNECT_TIMEOUT_MS.toLong())
        return configured.coerceIn(MIN_ADMIN_CONNECT_TIMEOUT_MS.toLong(), upperBound).toInt()
    }

    private fun parseJsonOrString(body: String): Any {
        if (body.isBlank()) return emptyMap<String, Any>()
        return try {
            objectMapper.readValue(body, Any::class.java)
        } catch (_: Exception) {
            mapOf("raw" to body)
        }
    }

    private fun errorResponse(status: HttpStatus, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(status)
            .body(ErrorResponse(error = message, timestamp = Instant.now().toString()))
    }

    private fun noBodyResponse(statusCode: HttpStatusCode): ResponseEntity<Any> {
        return ResponseEntity.status(statusCode).build<Any>()
    }

    private fun bodyResponse(statusCode: HttpStatusCode, body: Any): ResponseEntity<Any> {
        return ResponseEntity.status(statusCode).body(body)
    }

    private fun observeProxyCall(
        serverName: String,
        method: HttpMethod,
        statusCode: Int,
        startedAtNanos: Long
    ) {
        val durationNanos = (System.nanoTime() - startedAtNanos).coerceAtLeast(0)
        val outcome = when {
            statusCode in 200..299 -> "success"
            statusCode in 400..499 -> "client_error"
            else -> "server_error"
        }
        val tags = arrayOf(
            "method", method.name,
            "status", statusCode.toString(),
            "outcome", outcome
        )
        meterRegistry?.counter(METRIC_PROXY_REQUESTS, *tags)?.increment()
        meterRegistry?.let { registry ->
            Timer.builder(METRIC_PROXY_DURATION)
                .tags(*tags)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS)
        }

        val durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos)
        if (statusCode >= 400) {
            logger.warn {
                "MCP access-policy proxy failed: server=$serverName method=${method.name} " +
                    "status=$statusCode durationMs=$durationMs"
            }
        } else {
            logger.debug {
                "MCP access-policy proxy succeeded: server=$serverName method=${method.name} " +
                    "status=$statusCode durationMs=$durationMs"
            }
        }
    }

    private enum class HttpMethod { GET, PUT, DELETE }

    companion object {
        private const val METRIC_PROXY_REQUESTS = "arc.reactor.mcp.access_policy.proxy.requests"
        private const val METRIC_PROXY_DURATION = "arc.reactor.mcp.access_policy.proxy.duration"
        private const val MAX_PROJECT_KEYS = 200
        private const val MAX_SPACE_KEYS = 200
        private const val MAX_JIRA_KEY_LENGTH = 50
        private const val MAX_SPACE_KEY_LENGTH = 64
        private const val DEFAULT_ADMIN_TIMEOUT_MS = 10_000L
        private const val MIN_ADMIN_TIMEOUT_MS = 100L
        private const val MAX_ADMIN_TIMEOUT_MS = 120_000L
        private const val DEFAULT_ADMIN_CONNECT_TIMEOUT_MS = 3_000L
        private const val MIN_ADMIN_CONNECT_TIMEOUT_MS = 100
        private const val MAX_ADMIN_CONNECT_TIMEOUT_MS = 30_000
        private val JIRA_PROJECT_KEY_PATTERN = Regex("^[A-Z][A-Z0-9_]*$")
        private val CONFLUENCE_SPACE_KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")
        private val objectMapper = jacksonObjectMapper()
    }
}

data class UpdateMcpAccessPolicyRequest(
    val allowedJiraProjectKeys: List<String> = emptyList(),
    val allowedConfluenceSpaceKeys: List<String> = emptyList()
)
