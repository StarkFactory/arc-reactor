package com.arc.reactor.controller

import com.arc.reactor.audit.recordAdminAudit
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
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeoutException

private val swaggerCatalogLogger = KotlinLogging.logger {}

/**
 * MCP Swagger Catalog 컨트롤러.
 *
 * 등록된 MCP 서버의 admin API를 프록시하여 Swagger OpenAPI 스펙 소스의
 * 생명주기(조회, 생성, 수정, 동기화, 리비전, diff, 퍼블리시)를 관리합니다.
 * 모든 요청은 HMAC 서명 및 admin token 인증을 거칩니다.
 *
 * @see McpServerStore
 * @see McpAdminWebClientFactory
 * @see McpAdminRequestSigner
 */
@Tag(name = "MCP Swagger Catalog", description = "Proxy Swagger MCP admin source lifecycle APIs (ADMIN)")
@RestController
@RequestMapping("/api/mcp/servers/{name}/swagger/sources")
class McpSwaggerCatalogController(
    private val mcpServerStore: McpServerStore,
    private val adminAuditStore: AdminAuditStore,
    private val meterRegistry: MeterRegistry? = null,
    private val adminWebClientFactory: McpAdminWebClientFactory = McpAdminWebClientFactory()
) {
    private val proxySupport = McpAdminProxySupport

    /** MCP admin API를 통해 Swagger 스펙 소스 목록을 조회한다. */
    @Operation(summary = "MCP admin API를 통해 Swagger 스펙 소스 목록 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Swagger sources"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found")
    ])
    @GetMapping
    suspend fun listSources(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name, ProxyMethod.GET, adminTarget(ADMIN_SPEC_SOURCES_PATH),
            null, actor, proxySupport.resolveRequestId(exchange)
        )
        recordAudit(name, actor, "LIST_SOURCES", response.statusCode.value())
        return response
    }

    /** MCP admin API를 통해 특정 Swagger 스펙 소스를 조회한다. */
    @Operation(summary = "MCP admin API를 통해 Swagger 스펙 소스 단건 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Swagger source"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server or source not found")
    ])
    @GetMapping("/{sourceName}")
    suspend fun getSource(
        @PathVariable name: String,
        @PathVariable sourceName: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name, ProxyMethod.GET,
            adminTarget("$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}"),
            null, actor, proxySupport.resolveRequestId(exchange)
        )
        recordAudit(name, actor, "GET_SOURCE", response.statusCode.value(), sourceName)
        return response
    }

    /** MCP admin API를 통해 새 Swagger 스펙 소스를 생성한다. */
    @Operation(summary = "MCP admin API를 통해 Swagger 스펙 소스 생성")
    @PostMapping
    suspend fun createSource(
        @PathVariable name: String,
        @Valid @RequestBody request: SwaggerSpecSourceRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name, ProxyMethod.POST, adminTarget(ADMIN_SPEC_SOURCES_PATH),
            request, actor, proxySupport.resolveRequestId(exchange)
        )
        recordAudit(name, actor, "CREATE_SOURCE", response.statusCode.value(), request.name)
        return response
    }

    /** MCP admin API를 통해 Swagger 스펙 소스를 수정한다. */
    @Operation(summary = "MCP admin API를 통해 Swagger 스펙 소스 수정")
    @PutMapping("/{sourceName}")
    suspend fun updateSource(
        @PathVariable name: String,
        @PathVariable sourceName: String,
        @Valid @RequestBody request: SwaggerSpecSourceUpdateRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name, ProxyMethod.PUT,
            adminTarget("$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}"),
            request, actor, proxySupport.resolveRequestId(exchange)
        )
        recordAudit(name, actor, "UPDATE_SOURCE", response.statusCode.value(), sourceName)
        return response
    }

    /** MCP admin API를 통해 Swagger 스펙 소스 동기화를 트리거한다. */
    @Operation(summary = "MCP admin API를 통해 Swagger 스펙 소스 동기화 트리거")
    @PostMapping("/{sourceName}/sync")
    suspend fun syncSource(
        @PathVariable name: String,
        @PathVariable sourceName: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name, ProxyMethod.POST,
            adminTarget("$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}/sync"),
            emptyMap<String, Any>(), actor, proxySupport.resolveRequestId(exchange)
        )
        recordAudit(name, actor, "SYNC_SOURCE", response.statusCode.value(), sourceName)
        return response
    }

    /** MCP admin API를 통해 Swagger 스펙 소스의 리비전 목록을 조회한다. */
    @Operation(summary = "MCP admin API를 통해 Swagger 스펙 소스 리비전 목록 조회")
    @GetMapping("/{sourceName}/revisions")
    suspend fun listRevisions(
        @PathVariable name: String,
        @PathVariable sourceName: String,
        @RequestParam(required = false) limit: Int?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name, ProxyMethod.GET,
            adminTarget("$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}/revisions", "limit" to limit?.toString()),
            null, actor, proxySupport.resolveRequestId(exchange)
        )
        val auditDetail = if (limit != null) "$sourceName?limit=$limit" else sourceName
        recordAudit(name, actor, "LIST_REVISIONS", response.statusCode.value(), auditDetail)
        return response
    }

    /** MCP admin API를 통해 Swagger 스펙 소스의 리비전 간 diff를 조회한다. */
    @Operation(summary = "MCP admin API를 통해 Swagger 스펙 소스 diff 조회")
    @GetMapping("/{sourceName}/diff")
    suspend fun getDiff(
        @PathVariable name: String,
        @PathVariable sourceName: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val target = adminTarget(
            "$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}/diff",
            "from" to from, "to" to to
        )
        val response = proxy(
            name, ProxyMethod.GET, target, null, actor, proxySupport.resolveRequestId(exchange)
        )
        val fromLabel = from?.takeIf { it.isNotBlank() } ?: "auto"
        val toLabel = to?.takeIf { it.isNotBlank() } ?: "auto"
        recordAudit(name, actor, "GET_DIFF", response.statusCode.value(), "$sourceName:$fromLabel->$toLabel")
        return response
    }

    /** MCP admin API를 통해 Swagger 스펙 소스 리비전을 퍼블리시한다. */
    @Operation(summary = "MCP admin API를 통해 Swagger 스펙 소스 리비전 퍼블리시")
    @PostMapping("/{sourceName}/publish")
    suspend fun publishRevision(
        @PathVariable name: String,
        @PathVariable sourceName: String,
        @Valid @RequestBody request: SwaggerPublishRevisionRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name, ProxyMethod.POST,
            adminTarget("$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}/publish"),
            request, actor, proxySupport.resolveRequestId(exchange)
        )
        recordAudit(name, actor, "PUBLISH_REVISION", response.statusCode.value(), "${sourceName}:${request.revisionId}")
        return response
    }

    // ── 프록시 코어 ──

    private suspend fun proxy(
        name: String, method: ProxyMethod, target: AdminRequestTarget,
        body: Any?, actor: String, requestId: String
    ): ResponseEntity<Any> {
        val startedAtNanos = System.nanoTime()
        val server = mcpServerStore.findByName(name)
            ?: return proxySupport.errorResponse(HttpStatus.NOT_FOUND, "MCP server '$name' not found")
                .also { observeCall(name, method, it.statusCode.value(), startedAtNanos) }

        val config = server.config
        val baseUrl = McpAdminUrlResolver.resolve(config)
            ?: return proxySupport.errorResponse(HttpStatus.BAD_REQUEST,
                "MCP server '$name' has invalid admin URL. Set absolute config.adminUrl or config.url(/sse) with http/https")
                .also { observeCall(name, method, it.statusCode.value(), startedAtNanos) }
        val token = config["adminToken"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return proxySupport.errorResponse(HttpStatus.BAD_REQUEST,
                "MCP server '$name' has no admin token. Set config.adminToken")
                .also { observeCall(name, method, it.statusCode.value(), startedAtNanos) }
        val hmacSettings = McpAdminHmacSettings.from(config)
        if (hmacSettings.required && !hmacSettings.isEnabled()) {
            return proxySupport.errorResponse(HttpStatus.BAD_REQUEST,
                "MCP server '$name' requires HMAC but config.adminHmacSecret is missing")
                .also { observeCall(name, method, it.statusCode.value(), startedAtNanos) }
        }
        val timeoutMs = proxySupport.resolveAdminTimeoutMs(config)
        val connectTimeoutMs = proxySupport.resolveAdminConnectTimeoutMs(config, timeoutMs)

        val response = executeProxyCall(
            method, baseUrl, connectTimeoutMs, timeoutMs,
            token, actor, requestId, hmacSettings, target, body, name
        )
        observeCall(name, method, response.statusCode.value(), startedAtNanos)
        return response
    }

    private suspend fun executeProxyCall(
        method: ProxyMethod, baseUrl: String,
        connectTimeoutMs: Int, timeoutMs: Long,
        token: String, actor: String, requestId: String,
        hmac: McpAdminHmacSettings, target: AdminRequestTarget,
        body: Any?, serverName: String
    ): ResponseEntity<Any> {
        val payloadJson = body?.let { proxySupport.serializeBody(it) }
        return try {
            val client = adminWebClientFactory.getClient(baseUrl, connectTimeoutMs, timeoutMs)
            when (method) {
                ProxyMethod.GET -> proxyGet(client, token, actor, requestId, hmac, target, payloadJson, timeoutMs)
                ProxyMethod.POST -> proxyWithBody(
                    client.post(), "POST", token, actor, requestId, hmac, target, payloadJson, timeoutMs
                )
                ProxyMethod.PUT -> proxyWithBody(
                    client.put(), "PUT", token, actor, requestId, hmac, target, payloadJson, timeoutMs
                )
            } ?: proxySupport.errorResponse(HttpStatus.BAD_GATEWAY, "MCP admin API returned no response")
        } catch (_: TimeoutException) {
            proxySupport.errorResponse(HttpStatus.GATEWAY_TIMEOUT, "MCP admin API timed out after ${timeoutMs}ms")
        } catch (e: Exception) {
            e.throwIfCancellation()
            swaggerCatalogLogger.warn(e) { "MCP 서버 '$serverName' Swagger 카탈로그 프록시 요청 실패" }
            proxySupport.errorResponse(HttpStatus.BAD_GATEWAY, "Failed to call MCP admin API")
        }
    }

    private suspend fun proxyGet(
        client: org.springframework.web.reactive.function.client.WebClient,
        token: String, actor: String, requestId: String,
        hmac: McpAdminHmacSettings, target: AdminRequestTarget,
        payloadJson: String?, timeoutMs: Long
    ): ResponseEntity<Any>? {
        return client.get()
            .uri { builder -> buildUri(builder, target) }
            .header("X-Admin-Token", token)
            .header("X-Admin-Actor", actor)
            .header("X-Request-Id", requestId)
            .headers { h -> proxySupport.applyHmacHeaders(h, hmac, "GET", target.path, target.rawQuery, payloadJson.orEmpty()) }
            .exchangeToMono { upstream ->
                upstream.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> proxySupport.toResponseEntity(upstream.statusCode(), body) }
            }
            .timeout(Duration.ofMillis(timeoutMs))
            .awaitSingleOrNull()
    }

    private suspend fun proxyWithBody(
        spec: org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec,
        methodName: String, token: String, actor: String, requestId: String,
        hmac: McpAdminHmacSettings, target: AdminRequestTarget,
        payloadJson: String?, timeoutMs: Long
    ): ResponseEntity<Any>? {
        val json = payloadJson ?: "{}"
        return spec
            .uri { builder -> buildUri(builder, target) }
            .header("X-Admin-Token", token)
            .header("X-Admin-Actor", actor)
            .header("X-Request-Id", requestId)
            .header("Content-Type", "application/json")
            .headers { h -> proxySupport.applyHmacHeaders(h, hmac, methodName, target.path, target.rawQuery, json) }
            .bodyValue(json)
            .exchangeToMono { upstream ->
                upstream.bodyToMono(String::class.java).defaultIfEmpty("")
                    .map { body -> proxySupport.toResponseEntity(upstream.statusCode(), body) }
            }
            .timeout(Duration.ofMillis(timeoutMs))
            .awaitSingleOrNull()
    }

    // ── 유틸리티 ──

    private fun recordAudit(
        serverName: String, actor: String, action: String,
        statusCode: Int, detail: String? = null
    ) {
        recordAdminAudit(
            store = adminAuditStore, category = "mcp_swagger_catalog",
            action = action, actor = actor,
            resourceType = "mcp_server", resourceId = serverName,
            detail = "status=$statusCode${detail?.let { ", detail=$it" } ?: ""}"
        )
    }

    private fun observeCall(
        serverName: String, method: ProxyMethod,
        statusCode: Int, startedAtNanos: Long
    ) {
        proxySupport.observeProxyCall(
            meterRegistry, METRIC_PREFIX, "MCP swagger catalog proxy",
            serverName, statusCode, startedAtNanos,
            extraTags = arrayOf("method", method.name)
        )
    }

    private fun encodePath(value: String): String =
        UriUtils.encodePathSegment(value, StandardCharsets.UTF_8)

    private fun buildUri(
        builder: org.springframework.web.util.UriBuilder,
        target: AdminRequestTarget
    ): java.net.URI {
        var current = builder.path(target.path)
        for ((key, value) in target.queryParams) {
            current = current.queryParam(key, value)
        }
        return current.build()
    }

    private fun adminTarget(
        basePath: String,
        vararg queryParams: Pair<String, String?>
    ): AdminRequestTarget {
        val normalized = queryParams.mapNotNull { (key, value) ->
            value?.takeIf { it.isNotBlank() }?.let { key to it }
        }
        val builder = UriComponentsBuilder.newInstance().path(basePath)
        for ((key, value) in normalized) {
            builder.queryParam(key, value)
        }
        val encoded = builder.build().encode().toUri()
        return AdminRequestTarget(
            path = encoded.rawPath?.takeIf { it.isNotBlank() } ?: basePath,
            rawQuery = encoded.rawQuery.orEmpty(),
            queryParams = normalized
        )
    }

    private enum class ProxyMethod { GET, POST, PUT }

    private data class AdminRequestTarget(
        val path: String,
        val rawQuery: String,
        val queryParams: List<Pair<String, String>>
    )

    companion object {
        private const val ADMIN_SPEC_SOURCES_PATH = "/admin/spec-sources"
        private const val METRIC_PREFIX = "arc.reactor.mcp.swagger_catalog"
    }
}

data class SwaggerSpecSourceRequest(
    @field:NotBlank(message = "name must not be blank")
    @field:Size(max = 200, message = "name must not exceed 200 characters")
    val name: String,
    @field:NotBlank(message = "url must not be blank")
    @field:Size(max = 2000, message = "url must not exceed 2000 characters")
    val url: String,
    val enabled: Boolean = true,
    @field:Size(max = 100, message = "syncCron must not exceed 100 characters")
    val syncCron: String? = null,
    @field:Size(max = 50, message = "jiraProjectKey must not exceed 50 characters")
    val jiraProjectKey: String? = null,
    @field:Size(max = 64, message = "confluenceSpaceKey must not exceed 64 characters")
    val confluenceSpaceKey: String? = null,
    @field:Size(max = 120, message = "bitbucketRepository must not exceed 120 characters")
    val bitbucketRepository: String? = null,
    @field:Size(max = 200, message = "serviceSlug must not exceed 200 characters")
    val serviceSlug: String? = null,
    @field:Size(max = 200, message = "ownerTeam must not exceed 200 characters")
    val ownerTeam: String? = null
)

data class SwaggerSpecSourceUpdateRequest(
    @field:Size(max = 2000, message = "url must not exceed 2000 characters")
    val url: String? = null,
    val enabled: Boolean? = null,
    @field:Size(max = 100, message = "syncCron must not exceed 100 characters")
    val syncCron: String? = null,
    @field:Size(max = 50, message = "jiraProjectKey must not exceed 50 characters")
    val jiraProjectKey: String? = null,
    @field:Size(max = 64, message = "confluenceSpaceKey must not exceed 64 characters")
    val confluenceSpaceKey: String? = null,
    @field:Size(max = 120, message = "bitbucketRepository must not exceed 120 characters")
    val bitbucketRepository: String? = null,
    @field:Size(max = 200, message = "serviceSlug must not exceed 200 characters")
    val serviceSlug: String? = null,
    @field:Size(max = 200, message = "ownerTeam must not exceed 200 characters")
    val ownerTeam: String? = null
)

data class SwaggerPublishRevisionRequest(
    @field:NotBlank(message = "revisionId must not be blank")
    @field:Size(max = 200, message = "revisionId must not exceed 200 characters")
    val revisionId: String
)
