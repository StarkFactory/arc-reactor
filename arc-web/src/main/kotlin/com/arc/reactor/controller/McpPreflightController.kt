package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.netty.http.client.PrematureCloseException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

@Tag(name = "MCP Preflight", description = "Proxy MCP readiness checks for admin consoles (ADMIN)")
@RestController
@RequestMapping("/api/mcp/servers/{name}/preflight")
class McpPreflightController(
    private val mcpServerStore: com.arc.reactor.mcp.McpServerStore,
    private val adminAuditStore: AdminAuditStore,
    private val meterRegistry: MeterRegistry? = null,
    private val adminWebClientFactory: McpAdminWebClientFactory = McpAdminWebClientFactory()
) {
    @Operation(summary = "Run MCP server admin preflight via proxy")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Preflight result"),
        ApiResponse(responseCode = "400", description = "Invalid MCP server configuration"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "MCP server not found"),
        ApiResponse(responseCode = "504", description = "Preflight timed out")
    ])
    @GetMapping
    suspend fun getPreflight(
        @PathVariable name: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val actor = currentActor(exchange)
        val response = proxyPreflight(
            name = name,
            actor = actor,
            requestId = resolveRequestId(exchange)
        )
        recordAdminAudit(
            store = adminAuditStore,
            category = "mcp_preflight",
            action = "READ",
            actor = actor,
            resourceType = "mcp_server",
            resourceId = name,
            detail = buildAuditDetail(response)
        )
        return response
    }

    private suspend fun proxyPreflight(
        name: String,
        actor: String,
        requestId: String
    ): ResponseEntity<Any> {
        val startedAtNanos = System.nanoTime()
        val server = mcpServerStore.findByName(name)
            ?: return errorResponse(
                status = HttpStatus.NOT_FOUND,
                message = "MCP server '$name' not found"
            ).also { observeProxyCall(name, it.statusCode.value(), startedAtNanos) }

        val config = server.config
        val baseUrl = McpAdminUrlResolver.resolve(config)
            ?: return errorResponse(
                status = HttpStatus.BAD_REQUEST,
                message = "MCP server '$name' has invalid admin URL. " +
                    "Set absolute config.adminUrl or config.url(/sse) with http/https"
            ).also { observeProxyCall(name, it.statusCode.value(), startedAtNanos) }
        val token = config["adminToken"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return errorResponse(
                status = HttpStatus.BAD_REQUEST,
                message = "MCP server '$name' has no admin token. Set config.adminToken"
            ).also { observeProxyCall(name, it.statusCode.value(), startedAtNanos) }
        val hmacSettings = McpAdminHmacSettings.from(config)
        if (hmacSettings.required && !hmacSettings.isEnabled()) {
            return errorResponse(
                status = HttpStatus.BAD_REQUEST,
                message = "MCP server '$name' requires HMAC but config.adminHmacSecret is missing"
            ).also { observeProxyCall(name, it.statusCode.value(), startedAtNanos) }
        }

        val timeoutMs = resolveAdminTimeoutMs(config)
        val connectTimeoutMs = resolveAdminConnectTimeoutMs(config, timeoutMs)

        val response: ResponseEntity<Any> = try {
            adminWebClientFactory.getClient(baseUrl, connectTimeoutMs, timeoutMs)
                .get()
                .uri(ADMIN_PREFLIGHT_PATH)
                .header("X-Admin-Token", token)
                .header("X-Admin-Actor", actor)
                .header("X-Request-Id", requestId)
                .headers { headers ->
                    applyHmacHeaders(
                        headers = headers,
                        settings = hmacSettings,
                        method = "GET",
                        path = ADMIN_PREFLIGHT_PATH,
                        query = "",
                        body = ""
                    )
                }
                .exchangeToMono { upstream ->
                    upstream.bodyToMono(String::class.java)
                        .defaultIfEmpty("")
                        .map { responseBody -> toResponseEntity(upstream.statusCode(), responseBody) }
                }
                .timeout(Duration.ofMillis(timeoutMs))
                .awaitSingleOrNull()
                ?: errorResponse(HttpStatus.BAD_GATEWAY, "MCP admin API returned no response")
        } catch (_: TimeoutException) {
            errorResponse(HttpStatus.GATEWAY_TIMEOUT, "MCP admin API timed out after ${timeoutMs}ms")
        } catch (_: PrematureCloseException) {
            errorResponse(HttpStatus.BAD_GATEWAY, "MCP admin API closed the connection unexpectedly")
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to proxy preflight request to MCP server '$name'" }
            errorResponse(
                status = HttpStatus.BAD_GATEWAY,
                message = "Failed to call MCP admin API: ${e.message}"
            )
        }

        observeProxyCall(name, response.statusCode.value(), startedAtNanos)
        return response
    }

    private fun applyHmacHeaders(
        headers: org.springframework.http.HttpHeaders,
        settings: McpAdminHmacSettings,
        method: String,
        path: String,
        query: String,
        body: String
    ) {
        val secret = settings.secret ?: return
        val signed = McpAdminRequestSigner.sign(
            method = method,
            path = path,
            query = query,
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

    private fun buildAuditDetail(response: ResponseEntity<Any>): String {
        val parts = mutableListOf("status=${response.statusCode.value()}")
        val body = response.body as? Map<*, *> ?: return parts.joinToString(", ")
        appendStringDetail(parts, "policySource", body["policySource"])
        appendBooleanDetail(parts, "ok", body["ok"])
        appendBooleanDetail(parts, "readyForProduction", body["readyForProduction"])
        appendSummaryDetail(parts, body["summary"])
        appendIssueDetail(parts, body["checks"])
        return parts.joinToString(", ")
    }

    private fun appendStringDetail(parts: MutableList<String>, key: String, value: Any?) {
        val normalized = value?.toString()?.trim().orEmpty()
        if (normalized.isNotBlank()) parts.add("$key=$normalized")
    }

    private fun appendBooleanDetail(parts: MutableList<String>, key: String, value: Any?) {
        val normalized = value as? Boolean ?: return
        parts.add("$key=$normalized")
    }

    private fun appendSummaryDetail(parts: MutableList<String>, summaryRaw: Any?) {
        val summary = summaryRaw as? Map<*, *> ?: return
        appendIntDetail(parts, "passCount", summary["passCount"])
        appendIntDetail(parts, "warnCount", summary["warnCount"])
        appendIntDetail(parts, "failCount", summary["failCount"])
    }

    private fun appendIntDetail(parts: MutableList<String>, key: String, value: Any?) {
        val normalized = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        } ?: return
        parts.add("$key=$normalized")
    }

    private fun appendIssueDetail(parts: MutableList<String>, checksRaw: Any?) {
        val issues = (checksRaw as? List<*>)
            ?.mapNotNull { describeIssue(it as? Map<*, *>) }
            ?.take(MAX_AUDIT_ISSUES)
            .orEmpty()
        if (issues.isNotEmpty()) {
            parts.add("issues=${issues.joinToString("|")}")
        }
    }

    private fun describeIssue(check: Map<*, *>?): String? {
        val status = check?.get("status")?.toString()?.trim()?.uppercase().orEmpty()
        if (status.isBlank() || status == "PASS" || status == "UP") return null

        val name = check?.get("name")?.toString()?.trim().orEmpty().ifBlank { "unknown_check" }
        val message = check?.get("message")?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?.replace(',', ';')
            ?.replace('|', '/')
        return if (message != null) "$name:$status:$message" else "$name:$status"
    }

    private fun observeProxyCall(
        serverName: String,
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
                "MCP preflight proxy failed: server=$serverName status=$statusCode durationMs=$durationMs"
            }
        } else {
            logger.debug {
                "MCP preflight proxy succeeded: server=$serverName status=$statusCode durationMs=$durationMs"
            }
        }
    }

    companion object {
        private const val METRIC_PROXY_REQUESTS = "arc.reactor.mcp.preflight.proxy.requests"
        private const val METRIC_PROXY_DURATION = "arc.reactor.mcp.preflight.proxy.duration"
        private const val DEFAULT_ADMIN_TIMEOUT_MS = 10_000L
        private const val MIN_ADMIN_TIMEOUT_MS = 100L
        private const val MAX_ADMIN_TIMEOUT_MS = 120_000L
        private const val DEFAULT_ADMIN_CONNECT_TIMEOUT_MS = 3_000L
        private const val MIN_ADMIN_CONNECT_TIMEOUT_MS = 100
        private const val MAX_ADMIN_CONNECT_TIMEOUT_MS = 30_000
        private const val ADMIN_PREFLIGHT_PATH = "/admin/preflight"
        private const val MAX_AUDIT_ISSUES = 3
        private val objectMapper = jacksonObjectMapper()
    }
}
