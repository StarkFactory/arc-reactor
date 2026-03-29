package com.arc.reactor.controller

import com.arc.reactor.audit.recordAdminAudit
import com.arc.reactor.audit.AdminAuditStore
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.netty.http.client.PrematureCloseException
import java.time.Duration
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * MCP Preflight 컨트롤러.
 *
 * MCP 서버의 admin API에 프록시 요청을 보내 준비 상태(readiness)를 점검합니다.
 * 관리 콘솔에서 서버 배포 전 사전 검증 용도로 사용됩니다.
 *
 * @see com.arc.reactor.mcp.McpServerStore
 * @see McpAdminWebClientFactory
 */
@Tag(name = "MCP Preflight", description = "Proxy MCP readiness checks for admin consoles (ADMIN)")
@RestController
@RequestMapping("/api/mcp/servers/{name}/preflight")
class McpPreflightController(
    private val mcpServerStore: com.arc.reactor.mcp.McpServerStore,
    private val adminAuditStore: AdminAuditStore,
    private val meterRegistry: MeterRegistry? = null,
    private val adminWebClientFactory: McpAdminWebClientFactory = McpAdminWebClientFactory()
) {
    private val proxy = McpAdminProxySupport

    /** MCP 서버 admin API를 프록시하여 preflight 점검을 실행한다. */
    @Operation(summary = "MCP 서버 admin preflight 점검 실행 (프록시)")
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
        val response = proxyPreflight(name, actor, proxy.resolveRequestId(exchange))
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
            ?: return proxy.errorResponse(HttpStatus.NOT_FOUND, "MCP server '$name' not found")
                .also { observeCall(name, it.statusCode.value(), startedAtNanos) }

        val config = server.config
        val baseUrl = McpAdminUrlResolver.resolve(config)
            ?: return proxy.errorResponse(HttpStatus.BAD_REQUEST,
                "MCP server '$name' has invalid admin URL. " +
                    "Set absolute config.adminUrl or config.url(/sse) with http/https")
                .also { observeCall(name, it.statusCode.value(), startedAtNanos) }
        val token = config["adminToken"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return proxy.errorResponse(HttpStatus.BAD_REQUEST,
                "MCP server '$name' has no admin token. Set config.adminToken")
                .also { observeCall(name, it.statusCode.value(), startedAtNanos) }
        val hmacSettings = McpAdminHmacSettings.from(config)
        if (hmacSettings.required && !hmacSettings.isEnabled()) {
            return proxy.errorResponse(HttpStatus.BAD_REQUEST,
                "MCP server '$name' requires HMAC but config.adminHmacSecret is missing")
                .also { observeCall(name, it.statusCode.value(), startedAtNanos) }
        }

        val timeoutMs = proxy.resolveAdminTimeoutMs(config)
        val connectTimeoutMs = proxy.resolveAdminConnectTimeoutMs(config, timeoutMs)

        val response = executeRequest(
            baseUrl, connectTimeoutMs, timeoutMs, token, actor, requestId, hmacSettings, name
        )
        observeCall(name, response.statusCode.value(), startedAtNanos)
        return response
    }

    private suspend fun executeRequest(
        baseUrl: String, connectTimeoutMs: Int, timeoutMs: Long,
        token: String, actor: String, requestId: String,
        hmacSettings: McpAdminHmacSettings, serverName: String
    ): ResponseEntity<Any> {
        return try {
            adminWebClientFactory.getClient(baseUrl, connectTimeoutMs, timeoutMs)
                .get()
                .uri(ADMIN_PREFLIGHT_PATH)
                .header("X-Admin-Token", token)
                .header("X-Admin-Actor", actor)
                .header("X-Request-Id", requestId)
                .headers { h ->
                    proxy.applyHmacHeaders(h, hmacSettings, "GET", ADMIN_PREFLIGHT_PATH, "", "")
                }
                .exchangeToMono { upstream ->
                    upstream.bodyToMono(String::class.java)
                        .defaultIfEmpty("")
                        .map { body -> proxy.toResponseEntity(upstream.statusCode(), body) }
                }
                .timeout(Duration.ofMillis(timeoutMs))
                .awaitSingleOrNull()
                ?: proxy.errorResponse(HttpStatus.BAD_GATEWAY, "MCP admin API returned no response")
        } catch (_: TimeoutException) {
            proxy.errorResponse(HttpStatus.GATEWAY_TIMEOUT, "MCP admin API timed out after ${timeoutMs}ms")
        } catch (_: PrematureCloseException) {
            proxy.errorResponse(HttpStatus.BAD_GATEWAY, "MCP admin API closed the connection unexpectedly")
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "MCP 서버 '$serverName' preflight 프록시 요청 실패" }
            proxy.errorResponse(HttpStatus.BAD_GATEWAY, "Failed to call MCP admin API")
        }
    }

    private fun observeCall(serverName: String, statusCode: Int, startedAtNanos: Long) {
        proxy.observeProxyCall(
            meterRegistry, METRIC_PREFIX, "MCP preflight proxy",
            serverName, statusCode, startedAtNanos
        )
    }

    // ── 감사 로그 상세 ──

    private fun buildAuditDetail(response: ResponseEntity<Any>): String {
        val parts = mutableListOf("status=${response.statusCode.value()}")
        val body = response.body as? Map<*, *> ?: return parts.joinToString(", ")
        appendDetail(parts, "policySource", body["policySource"])
        appendBoolDetail(parts, "ok", body["ok"])
        appendBoolDetail(parts, "readyForProduction", body["readyForProduction"])
        appendSummaryDetail(parts, body["summary"])
        appendIssueDetail(parts, body["checks"])
        return parts.joinToString(", ")
    }

    private fun appendDetail(parts: MutableList<String>, key: String, value: Any?) {
        val s = value?.toString()?.trim().orEmpty()
        if (s.isNotBlank()) parts.add("$key=$s")
    }

    private fun appendBoolDetail(parts: MutableList<String>, key: String, value: Any?) {
        (value as? Boolean)?.let { parts.add("$key=$it") }
    }

    private fun appendSummaryDetail(parts: MutableList<String>, raw: Any?) {
        val summary = raw as? Map<*, *> ?: return
        appendIntDetail(parts, "passCount", summary["passCount"])
        appendIntDetail(parts, "warnCount", summary["warnCount"])
        appendIntDetail(parts, "failCount", summary["failCount"])
    }

    private fun appendIntDetail(parts: MutableList<String>, key: String, value: Any?) {
        val n = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        } ?: return
        parts.add("$key=$n")
    }

    private fun appendIssueDetail(parts: MutableList<String>, checksRaw: Any?) {
        val issues = (checksRaw as? List<*>)
            ?.mapNotNull { describeIssue(it as? Map<*, *>) }
            ?.take(MAX_AUDIT_ISSUES).orEmpty()
        if (issues.isNotEmpty()) parts.add("issues=${issues.joinToString("|")}")
    }

    private fun describeIssue(check: Map<*, *>?): String? {
        val status = check?.get("status")?.toString()?.trim()?.uppercase().orEmpty()
        if (status.isBlank() || status == "PASS" || status == "UP") return null
        val name = check?.get("name")?.toString()?.trim().orEmpty().ifBlank { "unknown_check" }
        val msg = check?.get("message")?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }?.replace(',', ';')?.replace('|', '/')
        return if (msg != null) "$name:$status:$msg" else "$name:$status"
    }

    companion object {
        private const val METRIC_PREFIX = "arc.reactor.mcp.preflight"
        private const val ADMIN_PREFLIGHT_PATH = "/admin/preflight"
        private const val MAX_AUDIT_ISSUES = 3
    }
}
