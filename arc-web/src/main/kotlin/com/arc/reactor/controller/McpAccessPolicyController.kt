package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.support.throwIfCancellation
import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Duration
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * MCP 서버별 admin 접근 정책 API 프록시 컨트롤러.
 *
 * Atlassian MCP와 Swagger MCP가 공유하는 admin UI 계약을 지원합니다.
 * 업스트림 서버는 구현하지 않는 필드를 무시할 수 있지만, Arc Reactor는
 * 접근 정책 관리를 위한 하나의 안정적인 API 표면을 유지합니다.
 *
 * @see McpServerStore
 * @see McpAdminWebClientFactory
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
    private val proxy = McpAdminProxySupport

    /** MCP 서버 admin API에서 현재 접근 정책을 조회한다. */
    @Operation(summary = "MCP 서버 admin API에서 접근 정책 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Current access policy"),
        ApiResponse(responseCode = "400", description = "Invalid MCP server configuration"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found")
    ])
    @GetMapping
    suspend fun getPolicy(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxyRequest(name, HttpMethod.GET, null, actor, proxy.resolveRequestId(exchange))
        recordAudit(name, actor, "READ", response.statusCode.value())
        return response
    }

    /** MCP 서버 admin API의 접근 정책을 수정한다. 요청 검증 후 프록시한다. */
    @Operation(summary = "MCP 서버 admin API 접근 정책 수정")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Access policy updated"),
        ApiResponse(responseCode = "400", description = "Invalid policy or MCP server configuration"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found")
    ])
    @PutMapping
    suspend fun updatePolicy(
        @PathVariable name: String,
        @RequestBody request: UpdateMcpAccessPolicyRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val validationError = validatePolicyRequest(request)
        if (validationError != null) {
            val response = proxy.errorResponse(HttpStatus.BAD_REQUEST, validationError)
            recordAudit(name, actor, "UPDATE", response.statusCode.value(), request, validationError)
            return response
        }
        val response = proxyRequest(name, HttpMethod.PUT, request, actor, proxy.resolveRequestId(exchange))
        recordAudit(name, actor, "UPDATE", response.statusCode.value(), request)
        return response
    }

    /** MCP 서버 admin API의 동적 정책을 초기화하여 환경 기본값으로 복원한다. */
    @Operation(summary = "MCP 서버 admin API 동적 정책 초기화")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Access policy cleared"),
        ApiResponse(responseCode = "400", description = "Invalid MCP server configuration"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found")
    ])
    @DeleteMapping
    suspend fun clearPolicy(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxyRequest(name, HttpMethod.DELETE, null, actor, proxy.resolveRequestId(exchange))
        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_access_policy",
            action = "DELETE",
            actor = actor,
            resourceType = "mcp_server",
            resourceId = name,
            detail = "status=${response.statusCode.value()}, reset_to_env_defaults=true"
        )
        return response
    }

    private suspend fun proxyRequest(
        name: String,
        method: HttpMethod,
        body: Any?,
        actor: String,
        requestId: String
    ): ResponseEntity<Any> {
        val startedAtNanos = System.nanoTime()
        val server = mcpServerStore.findByName(name)
            ?: return proxy.errorResponse(HttpStatus.NOT_FOUND, "MCP server '$name' not found")
                .also { observeCall(name, method, it.statusCode.value(), startedAtNanos) }

        val config = server.config
        val baseUrl = McpAdminUrlResolver.resolve(config)
            ?: return proxy.errorResponse(HttpStatus.BAD_REQUEST,
                "MCP server '$name' has invalid admin URL. " +
                    "Set absolute config.adminUrl or config.url(/sse) with http/https")
                .also { observeCall(name, method, it.statusCode.value(), startedAtNanos) }
        val token = config["adminToken"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return proxy.errorResponse(HttpStatus.BAD_REQUEST,
                "MCP server '$name' has no admin token. Set config.adminToken")
                .also { observeCall(name, method, it.statusCode.value(), startedAtNanos) }
        val hmacSettings = McpAdminHmacSettings.from(config)
        if (hmacSettings.required && !hmacSettings.isEnabled()) {
            return proxy.errorResponse(HttpStatus.BAD_REQUEST,
                "MCP server '$name' requires HMAC but config.adminHmacSecret is missing")
                .also { observeCall(name, method, it.statusCode.value(), startedAtNanos) }
        }
        val timeoutMs = proxy.resolveAdminTimeoutMs(config)
        val connectTimeoutMs = proxy.resolveAdminConnectTimeoutMs(config, timeoutMs)

        val response = executeProxyCall(
            method, baseUrl, connectTimeoutMs, timeoutMs,
            token, actor, requestId, hmacSettings, body, name
        )
        observeCall(name, method, response.statusCode.value(), startedAtNanos)
        return response
    }

    private suspend fun executeProxyCall(
        method: HttpMethod, baseUrl: String,
        connectTimeoutMs: Int, timeoutMs: Long,
        token: String, actor: String, requestId: String,
        hmacSettings: McpAdminHmacSettings, body: Any?, serverName: String
    ): ResponseEntity<Any> {
        return try {
            val client = adminWebClientFactory.getClient(baseUrl, connectTimeoutMs, timeoutMs)
            when (method) {
                HttpMethod.GET -> proxyGet(client, token, actor, requestId, hmacSettings, timeoutMs)
                HttpMethod.PUT -> proxyPut(client, token, actor, requestId, hmacSettings, body, timeoutMs)
                HttpMethod.DELETE -> proxyDelete(client, token, actor, requestId, hmacSettings, timeoutMs)
            } ?: proxy.errorResponse(HttpStatus.BAD_GATEWAY, "MCP admin API returned no response")
        } catch (_: TimeoutException) {
            proxy.errorResponse(HttpStatus.GATEWAY_TIMEOUT, "MCP admin API timed out after ${timeoutMs}ms")
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to proxy access-policy request to MCP server '$serverName'" }
            proxy.errorResponse(HttpStatus.BAD_GATEWAY, "Failed to call MCP admin API")
        }
    }

    private suspend fun proxyGet(
        client: org.springframework.web.reactive.function.client.WebClient,
        token: String, actor: String, requestId: String,
        hmac: McpAdminHmacSettings, timeoutMs: Long
    ): ResponseEntity<Any>? {
        return client.get()
            .uri(ADMIN_ACCESS_POLICY_PATH)
            .header("X-Admin-Token", token)
            .header("X-Admin-Actor", actor)
            .header("X-Request-Id", requestId)
            .headers { h -> proxy.applyHmacHeaders(h, hmac, "GET", ADMIN_ACCESS_POLICY_PATH, "", "") }
            .exchangeToMono { r ->
                r.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> proxy.toResponseEntity(r.statusCode(), body) }
            }
            .timeout(Duration.ofMillis(timeoutMs))
            .awaitSingleOrNull()
    }

    private suspend fun proxyPut(
        client: org.springframework.web.reactive.function.client.WebClient,
        token: String, actor: String, requestId: String,
        hmac: McpAdminHmacSettings, body: Any?, timeoutMs: Long
    ): ResponseEntity<Any>? {
        val json = proxy.serializeBody(body)
        return client.put()
            .uri(ADMIN_ACCESS_POLICY_PATH)
            .header("X-Admin-Token", token)
            .header("X-Admin-Actor", actor)
            .header("X-Request-Id", requestId)
            .header("Content-Type", "application/json")
            .headers { h -> proxy.applyHmacHeaders(h, hmac, "PUT", ADMIN_ACCESS_POLICY_PATH, "", json) }
            .bodyValue(json)
            .exchangeToMono { r ->
                r.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { responseBody -> proxy.toResponseEntity(r.statusCode(), responseBody) }
            }
            .timeout(Duration.ofMillis(timeoutMs))
            .awaitSingleOrNull()
    }

    private suspend fun proxyDelete(
        client: org.springframework.web.reactive.function.client.WebClient,
        token: String, actor: String, requestId: String,
        hmac: McpAdminHmacSettings, timeoutMs: Long
    ): ResponseEntity<Any>? {
        return client.delete()
            .uri(ADMIN_ACCESS_POLICY_PATH)
            .header("X-Admin-Token", token)
            .header("X-Admin-Actor", actor)
            .header("X-Request-Id", requestId)
            .headers { h -> proxy.applyHmacHeaders(h, hmac, "DELETE", ADMIN_ACCESS_POLICY_PATH, "", "") }
            .exchangeToMono { r ->
                r.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { responseBody -> proxy.toResponseEntity(r.statusCode(), responseBody) }
            }
            .timeout(Duration.ofMillis(timeoutMs))
            .awaitSingleOrNull()
    }

    // ── 검증 ──

    private fun validatePolicyRequest(request: UpdateMcpAccessPolicyRequest): String? {
        validateKeys("allowedJiraProjectKeys", request.allowedJiraProjectKeys,
            MAX_PROJECT_KEYS, MAX_JIRA_KEY_LENGTH, JIRA_PROJECT_KEY_PATTERN)?.let { return it }
        validateKeys("allowedConfluenceSpaceKeys", request.allowedConfluenceSpaceKeys,
            MAX_SPACE_KEYS, MAX_SPACE_KEY_LENGTH, CONFLUENCE_SPACE_KEY_PATTERN)?.let { return it }
        validateKeys("allowedBitbucketRepositories", request.allowedBitbucketRepositories,
            MAX_BITBUCKET_REPOS, MAX_BITBUCKET_REPO_LENGTH, BITBUCKET_REPO_PATTERN)?.let { return it }
        validateKeys("allowedSourceNames", request.allowedSourceNames,
            MAX_SOURCE_NAMES, MAX_SOURCE_NAME_LENGTH, SOURCE_NAME_PATTERN)?.let { return it }
        return null
    }

    private fun validateKeys(
        fieldName: String, keys: List<String>,
        maxItems: Int, maxLength: Int, pattern: Regex
    ): String? {
        if (keys.size > maxItems) return "$fieldName must not exceed $maxItems items"
        for ((index, rawKey) in keys.withIndex()) {
            val key = rawKey.trim()
            if (key.isEmpty()) return "$fieldName[$index] must not be blank"
            if (rawKey != key) return "$fieldName[$index] must not contain leading or trailing whitespace"
            if (key.length > maxLength) return "$fieldName[$index] must not exceed $maxLength characters"
            if (!pattern.matches(key)) return "$fieldName[$index] has invalid format"
        }
        return null
    }

    // ── 감사 ──

    private fun recordAudit(
        name: String, actor: String, action: String,
        statusCode: Int, request: UpdateMcpAccessPolicyRequest? = null,
        validationError: String? = null
    ) {
        val detail = if (request != null) buildAuditDetail(statusCode, request, validationError)
        else "status=$statusCode"
        recordAdminAudit(
            store = adminAuditStore, category = "mcp_access_policy",
            action = action, actor = actor,
            resourceType = "mcp_server", resourceId = name, detail = detail
        )
    }

    private fun buildAuditDetail(
        statusCode: Int, request: UpdateMcpAccessPolicyRequest,
        validationError: String? = null
    ): String {
        val parts = mutableListOf(
            "status=$statusCode",
            "jiraProjects=${request.allowedJiraProjectKeys.size}",
            "confluenceSpaces=${request.allowedConfluenceSpaceKeys.size}",
            "bitbucketRepos=${request.allowedBitbucketRepositories.size}",
            "sourceNames=${request.allowedSourceNames.size}",
            "allowPreviewReads=${request.allowPreviewReads}",
            "allowPreviewWrites=${request.allowPreviewWrites}",
            "allowDirectUrlLoads=${request.allowDirectUrlLoads}",
            "publishedOnly=${request.publishedOnly}"
        )
        if (!validationError.isNullOrBlank()) {
            parts.add("validationError=$validationError")
        }
        return parts.joinToString(", ")
    }

    private fun observeCall(
        serverName: String, method: HttpMethod,
        statusCode: Int, startedAtNanos: Long
    ) {
        proxy.observeProxyCall(
            meterRegistry, METRIC_PREFIX, "MCP access-policy proxy",
            serverName, statusCode, startedAtNanos,
            extraTags = arrayOf("method", method.name)
        )
    }

    private enum class HttpMethod { GET, PUT, DELETE }

    companion object {
        private const val METRIC_PREFIX = "arc.reactor.mcp.access_policy"
        private const val MAX_PROJECT_KEYS = 200
        private const val MAX_SPACE_KEYS = 200
        private const val MAX_BITBUCKET_REPOS = 200
        private const val MAX_SOURCE_NAMES = 100
        private const val MAX_JIRA_KEY_LENGTH = 50
        private const val MAX_SPACE_KEY_LENGTH = 64
        private const val MAX_BITBUCKET_REPO_LENGTH = 120
        private const val MAX_SOURCE_NAME_LENGTH = 200
        private const val ADMIN_ACCESS_POLICY_PATH = "/admin/access-policy"
        private val JIRA_PROJECT_KEY_PATTERN = Regex("^[A-Z][A-Z0-9_]*$")
        private val CONFLUENCE_SPACE_KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")
        private val BITBUCKET_REPO_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        private val SOURCE_NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")
    }
}

data class UpdateMcpAccessPolicyRequest(
    val allowedJiraProjectKeys: List<String> = emptyList(),
    val allowedConfluenceSpaceKeys: List<String> = emptyList(),
    val allowedBitbucketRepositories: List<String> = emptyList(),
    val allowedSourceNames: List<String> = emptyList(),
    val allowPreviewReads: Boolean? = null,
    val allowPreviewWrites: Boolean? = null,
    val allowDirectUrlLoads: Boolean? = null,
    val publishedOnly: Boolean? = null
)
