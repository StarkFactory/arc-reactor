package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val swaggerCatalogLogger = KotlinLogging.logger {}

@Tag(name = "MCP Swagger Catalog", description = "Proxy Swagger MCP admin source lifecycle APIs (ADMIN)")
@RestController
@RequestMapping("/api/mcp/servers/{name}/swagger/sources")
class McpSwaggerCatalogController(
    private val mcpServerStore: McpServerStore,
    private val adminAuditStore: AdminAuditStore,
    private val meterRegistry: MeterRegistry? = null,
    private val adminWebClientFactory: McpAdminWebClientFactory = McpAdminWebClientFactory()
) {
    @Operation(summary = "List Swagger spec sources via MCP admin API")
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
            name = name,
            method = ProxyMethod.GET,
            adminTarget = adminTarget(ADMIN_SPEC_SOURCES_PATH),
            body = null,
            actor = actor,
            requestId = resolveRequestId(exchange)
        )
        recordAudit(name, actor, "LIST_SOURCES", response.statusCode.value())
        return response
    }

    @Operation(summary = "Get Swagger spec source via MCP admin API")
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
            name = name,
            method = ProxyMethod.GET,
            adminTarget = adminTarget("$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}"),
            body = null,
            actor = actor,
            requestId = resolveRequestId(exchange)
        )
        recordAudit(name, actor, "GET_SOURCE", response.statusCode.value(), sourceName)
        return response
    }

    @Operation(summary = "Create Swagger spec source via MCP admin API")
    @PostMapping
    suspend fun createSource(
        @PathVariable name: String,
        @RequestBody request: SwaggerSpecSourceRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name = name,
            method = ProxyMethod.POST,
            adminTarget = adminTarget(ADMIN_SPEC_SOURCES_PATH),
            body = request,
            actor = actor,
            requestId = resolveRequestId(exchange)
        )
        recordAudit(name, actor, "CREATE_SOURCE", response.statusCode.value(), request.name)
        return response
    }

    @Operation(summary = "Update Swagger spec source via MCP admin API")
    @PutMapping("/{sourceName}")
    suspend fun updateSource(
        @PathVariable name: String,
        @PathVariable sourceName: String,
        @RequestBody request: SwaggerSpecSourceUpdateRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name = name,
            method = ProxyMethod.PUT,
            adminTarget = adminTarget("$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}"),
            body = request,
            actor = actor,
            requestId = resolveRequestId(exchange)
        )
        recordAudit(name, actor, "UPDATE_SOURCE", response.statusCode.value(), sourceName)
        return response
    }

    @Operation(summary = "Trigger Swagger spec source sync via MCP admin API")
    @PostMapping("/{sourceName}/sync")
    suspend fun syncSource(
        @PathVariable name: String,
        @PathVariable sourceName: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name = name,
            method = ProxyMethod.POST,
            adminTarget = adminTarget("$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}/sync"),
            body = emptyMap<String, Any>(),
            actor = actor,
            requestId = resolveRequestId(exchange)
        )
        recordAudit(name, actor, "SYNC_SOURCE", response.statusCode.value(), sourceName)
        return response
    }

    @Operation(summary = "List Swagger spec source revisions via MCP admin API")
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
            name = name,
            method = ProxyMethod.GET,
            adminTarget = adminTarget(
                "$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}/revisions",
                "limit" to limit?.toString()
            ),
            body = null,
            actor = actor,
            requestId = resolveRequestId(exchange)
        )
        val auditDetail = if (limit != null) "$sourceName?limit=$limit" else sourceName
        recordAudit(name, actor, "LIST_REVISIONS", response.statusCode.value(), auditDetail)
        return response
    }

    @Operation(summary = "Get Swagger spec source diff via MCP admin API")
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
        val adminTarget = adminTarget(
            "$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}/diff",
            "from" to from,
            "to" to to
        )
        val response = proxy(
            name = name,
            method = ProxyMethod.GET,
            adminTarget = adminTarget,
            body = null,
            actor = actor,
            requestId = resolveRequestId(exchange)
        )
        val fromLabel = from?.takeIf { it.isNotBlank() } ?: "auto"
        val toLabel = to?.takeIf { it.isNotBlank() } ?: "auto"
        recordAudit(name, actor, "GET_DIFF", response.statusCode.value(), "$sourceName:$fromLabel->$toLabel")
        return response
    }

    @Operation(summary = "Publish Swagger spec source revision via MCP admin API")
    @PostMapping("/{sourceName}/publish")
    suspend fun publishRevision(
        @PathVariable name: String,
        @PathVariable sourceName: String,
        @RequestBody request: SwaggerPublishRevisionRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val actor = currentActor(exchange)
        val response = proxy(
            name = name,
            method = ProxyMethod.POST,
            adminTarget = adminTarget("$ADMIN_SPEC_SOURCES_PATH/${encodePath(sourceName)}/publish"),
            body = request,
            actor = actor,
            requestId = resolveRequestId(exchange)
        )
        recordAudit(name, actor, "PUBLISH_REVISION", response.statusCode.value(), "${sourceName}:${request.revisionId}")
        return response
    }

    private suspend fun proxy(
        name: String,
        method: ProxyMethod,
        adminTarget: AdminRequestTarget,
        body: Any?,
        actor: String,
        requestId: String
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
                message = "MCP server '$name' has invalid admin URL. Set absolute config.adminUrl or config.url(/sse) with http/https"
            ).also { observeProxyCall(name, method, it.statusCode.value(), startedAtNanos) }
        val token = config["adminToken"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return errorResponse(
                status = HttpStatus.BAD_REQUEST,
                message = "MCP server '$name' has no admin token. Set config.adminToken"
            ).also { observeProxyCall(name, method, it.statusCode.value(), startedAtNanos) }
        val hmacSettings = McpAdminHmacSettings.from(config)
        if (hmacSettings.required && !hmacSettings.isEnabled()) {
            return errorResponse(
                status = HttpStatus.BAD_REQUEST,
                message = "MCP server '$name' requires HMAC but config.adminHmacSecret is missing"
            ).also { observeProxyCall(name, method, it.statusCode.value(), startedAtNanos) }
        }
        val timeoutMs = resolveAdminTimeoutMs(config)
        val connectTimeoutMs = resolveAdminConnectTimeoutMs(config, timeoutMs)

        val response: ResponseEntity<Any> = try {
            val client = adminWebClientFactory.getClient(baseUrl, connectTimeoutMs, timeoutMs)
            val payloadJson = body?.let { objectMapper.writeValueAsString(it) }
            when (method) {
                ProxyMethod.GET -> client.get()
                    .uri { builder -> buildUri(builder, adminTarget) }
                    .header("X-Admin-Token", token)
                    .header("X-Admin-Actor", actor)
                    .header("X-Request-Id", requestId)
                    .headers { headers ->
                        applyHmacHeaders(headers, hmacSettings, "GET", adminTarget, payloadJson.orEmpty())
                    }
                    .exchangeToMono { upstream ->
                        upstream.bodyToMono(String::class.java)
                            .defaultIfEmpty("")
                            .map { bodyText -> toResponseEntity(upstream.statusCode(), bodyText) }
                    }
                    .timeout(Duration.ofMillis(timeoutMs))
                    .awaitSingleOrNull()
                ProxyMethod.POST -> client.post()
                    .uri { builder -> buildUri(builder, adminTarget) }
                    .header("X-Admin-Token", token)
                    .header("X-Admin-Actor", actor)
                    .header("X-Request-Id", requestId)
                    .let { requestSpec ->
                        val json = payloadJson ?: "{}"
                        requestSpec
                            .header("Content-Type", "application/json")
                            .headers { headers ->
                                applyHmacHeaders(headers, hmacSettings, "POST", adminTarget, json)
                            }
                            .bodyValue(json)
                    }
                    .exchangeToMono { upstream ->
                        upstream.bodyToMono(String::class.java)
                            .defaultIfEmpty("")
                            .map { bodyText -> toResponseEntity(upstream.statusCode(), bodyText) }
                    }
                    .timeout(Duration.ofMillis(timeoutMs))
                    .awaitSingleOrNull()
                ProxyMethod.PUT -> client.put()
                    .uri { builder -> buildUri(builder, adminTarget) }
                    .header("X-Admin-Token", token)
                    .header("X-Admin-Actor", actor)
                    .header("X-Request-Id", requestId)
                    .let { requestSpec ->
                        val json = payloadJson ?: "{}"
                        requestSpec
                            .header("Content-Type", "application/json")
                            .headers { headers ->
                                applyHmacHeaders(headers, hmacSettings, "PUT", adminTarget, json)
                            }
                            .bodyValue(json)
                    }
                    .exchangeToMono { upstream ->
                        upstream.bodyToMono(String::class.java)
                            .defaultIfEmpty("")
                            .map { bodyText -> toResponseEntity(upstream.statusCode(), bodyText) }
                    }
                    .timeout(Duration.ofMillis(timeoutMs))
                    .awaitSingleOrNull()
            } ?: errorResponse(HttpStatus.BAD_GATEWAY, "MCP admin API returned no response")
        } catch (_: TimeoutException) {
            errorResponse(HttpStatus.GATEWAY_TIMEOUT, "MCP admin API timed out after ${timeoutMs}ms")
        } catch (e: Exception) {
            e.throwIfCancellation()
            swaggerCatalogLogger.warn(e) { "Failed to proxy Swagger catalog request to MCP server '$name'" }
            errorResponse(HttpStatus.BAD_GATEWAY, "Failed to call MCP admin API")
        }

        observeProxyCall(name, method, response.statusCode.value(), startedAtNanos)
        return response
    }

    private fun applyHmacHeaders(
        headers: org.springframework.http.HttpHeaders,
        settings: McpAdminHmacSettings,
        method: String,
        adminTarget: AdminRequestTarget,
        body: String
    ) {
        val secret = settings.secret ?: return
        val signed = McpAdminRequestSigner.sign(
            method = method,
            path = adminTarget.path,
            query = adminTarget.rawQuery,
            body = body,
            secret = secret
        )
        headers.set("X-Admin-Timestamp", signed.timestamp)
        headers.set("X-Admin-Signature", signed.signature)
    }

    private fun toResponseEntity(statusCode: HttpStatusCode, body: String): ResponseEntity<Any> {
        if (statusCode == HttpStatus.NO_CONTENT || body.isBlank()) {
            return ResponseEntity.status(statusCode).build()
        }
        return ResponseEntity.status(statusCode).body(parseJsonOrString(body))
    }

    private fun parseJsonOrString(body: String): Any {
        if (body.isBlank()) return emptyMap<String, Any>()
        return try {
            objectMapper.readValue(body, Any::class.java)
        } catch (_: Exception) {
            mapOf("raw" to body)
        }
    }

    private fun recordAudit(
        serverName: String, actor: String, action: String,
        statusCode: Int, detail: String? = null
    ) {
        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_swagger_catalog",
            action = action,
            actor = actor,
            resourceType = "mcp_server",
            resourceId = serverName,
            detail = "status=$statusCode${detail?.let { ", detail=$it" } ?: ""}"
        )
    }

    private fun resolveRequestId(exchange: ServerWebExchange): String {
        val requestId = exchange.request.headers.getFirst("X-Request-Id")?.trim().orEmpty()
        if (requestId.isNotBlank()) return requestId
        val correlationId = exchange.request.headers.getFirst("X-Correlation-Id")?.trim().orEmpty()
        if (correlationId.isNotBlank()) return correlationId
        return "arc-${UUID.randomUUID()}"
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

    private fun errorResponse(status: HttpStatus, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(status)
            .body(ErrorResponse(error = message, timestamp = Instant.now().toString()))
    }

    private fun observeProxyCall(
        serverName: String,
        method: ProxyMethod,
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
            swaggerCatalogLogger.warn {
                "MCP swagger catalog proxy failed: server=$serverName method=${method.name} status=$statusCode durationMs=$durationMs"
            }
        } else {
            swaggerCatalogLogger.debug {
                "MCP swagger catalog proxy succeeded: server=$serverName method=${method.name} status=$statusCode durationMs=$durationMs"
            }
        }
    }

    private fun encodePath(value: String): String = UriUtils.encodePathSegment(value, StandardCharsets.UTF_8)

    private fun buildUri(
        builder: org.springframework.web.util.UriBuilder,
        adminTarget: AdminRequestTarget
    ): java.net.URI {
        var current = builder.path(adminTarget.path)
        adminTarget.queryParams.forEach { (key, value) ->
            current = current.queryParam(key, value)
        }
        return current.build()
    }

    private fun adminTarget(basePath: String, vararg queryParams: Pair<String, String?>): AdminRequestTarget {
        val normalizedQueryParams = queryParams.mapNotNull { (key, value) ->
            value?.takeIf { it.isNotBlank() }?.let { key to it }
        }
        val encoded = UriComponentsBuilder.newInstance().path(basePath).apply {
            normalizedQueryParams.forEach { (key, value) -> queryParam(key, value) }
        }.build().encode().toUri()
        val resolvedPath = encoded.rawPath?.takeIf { it.isNotBlank() } ?: basePath
        val rawQuery = encoded.rawQuery.orEmpty()
        return AdminRequestTarget(
            path = resolvedPath,
            rawQuery = rawQuery,
            queryParams = normalizedQueryParams
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
        private const val DEFAULT_ADMIN_TIMEOUT_MS = 10_000L
        private const val MIN_ADMIN_TIMEOUT_MS = 100L
        private const val MAX_ADMIN_TIMEOUT_MS = 120_000L
        private const val DEFAULT_ADMIN_CONNECT_TIMEOUT_MS = 3_000L
        private const val MIN_ADMIN_CONNECT_TIMEOUT_MS = 100
        private const val MAX_ADMIN_CONNECT_TIMEOUT_MS = 30_000
        private const val METRIC_PROXY_REQUESTS = "arc.reactor.mcp.swagger_catalog.proxy.requests"
        private const val METRIC_PROXY_DURATION = "arc.reactor.mcp.swagger_catalog.proxy.duration"
        private val objectMapper = jacksonObjectMapper()
    }
}

data class SwaggerSpecSourceRequest(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val syncCron: String? = null,
    val jiraProjectKey: String? = null,
    val confluenceSpaceKey: String? = null,
    val bitbucketRepository: String? = null,
    val serviceSlug: String? = null,
    val ownerTeam: String? = null
)

data class SwaggerSpecSourceUpdateRequest(
    val url: String? = null,
    val enabled: Boolean? = null,
    val syncCron: String? = null,
    val jiraProjectKey: String? = null,
    val confluenceSpaceKey: String? = null,
    val bitbucketRepository: String? = null,
    val serviceSlug: String? = null,
    val ownerTeam: String? = null
)

data class SwaggerPublishRevisionRequest(
    val revisionId: String
)
