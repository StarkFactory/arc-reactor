package com.arc.reactor.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import mu.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

private val proxyLogger = KotlinLogging.logger {}

/**
 * MCP admin API 프록시 컨트롤러 공통 지원.
 *
 * McpSwaggerCatalogController, McpAccessPolicyController, McpPreflightController 등
 * MCP admin 프록시 컨트롤러에서 반복되는 유틸리티 로직을 한 곳에 모아 중복을 제거한다.
 */
internal object McpAdminProxySupport {

    private val objectMapper = jacksonObjectMapper()
    private val CRLF_REGEX = Regex("[\\r\\n]")

    private const val DEFAULT_ADMIN_TIMEOUT_MS = 10_000L
    private const val MIN_ADMIN_TIMEOUT_MS = 100L
    private const val MAX_ADMIN_TIMEOUT_MS = 120_000L
    private const val DEFAULT_ADMIN_CONNECT_TIMEOUT_MS = 3_000L
    private const val MIN_ADMIN_CONNECT_TIMEOUT_MS = 100
    private const val MAX_ADMIN_CONNECT_TIMEOUT_MS = 30_000

    /** MCP 서버 config에서 admin API 타임아웃(ms)을 해석한다. */
    fun resolveAdminTimeoutMs(config: Map<String, Any>): Long {
        val configured = when (val raw = config["adminTimeoutMs"]) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: DEFAULT_ADMIN_TIMEOUT_MS
        return configured.coerceIn(MIN_ADMIN_TIMEOUT_MS, MAX_ADMIN_TIMEOUT_MS)
    }

    /** MCP 서버 config에서 admin API 연결 타임아웃(ms)을 해석한다. */
    fun resolveAdminConnectTimeoutMs(
        config: Map<String, Any>,
        requestTimeoutMs: Long
    ): Int {
        val configured = when (val raw = config["adminConnectTimeoutMs"]) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: DEFAULT_ADMIN_CONNECT_TIMEOUT_MS
        val upperBound = minOf(
            requestTimeoutMs,
            MAX_ADMIN_CONNECT_TIMEOUT_MS.toLong()
        )
        return configured.coerceIn(
            MIN_ADMIN_CONNECT_TIMEOUT_MS.toLong(),
            upperBound
        ).toInt()
    }

    /** 요청 헤더에서 요청 ID를 추출하거나 새로 생성한다. */
    fun resolveRequestId(exchange: ServerWebExchange): String {
        val requestId = sanitizeHeaderValue(
            exchange.request.headers.getFirst("X-Request-Id")
        )
        if (requestId.isNotBlank()) return requestId
        val correlationId = sanitizeHeaderValue(
            exchange.request.headers.getFirst("X-Correlation-Id")
        )
        if (correlationId.isNotBlank()) return correlationId
        return "arc-${UUID.randomUUID()}"
    }

    /** CRLF 인젝션 방지 및 길이 제한 (로그·헤더 안전성). */
    private fun sanitizeHeaderValue(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim()
            .replace(CRLF_REGEX, "")
            .take(128)
    }

    /** JSON 파싱을 시도하고, 실패하면 원시 문자열을 감싸서 반환한다. */
    fun parseJsonOrString(body: String): Any {
        if (body.isBlank()) return emptyMap<String, Any>()
        return try {
            objectMapper.readValue(body, Any::class.java)
        } catch (_: Exception) {
            mapOf("raw" to body)
        }
    }

    /** 표준 오류 응답을 생성한다. */
    fun errorResponse(
        status: HttpStatus,
        message: String
    ): ResponseEntity<Any> {
        return ResponseEntity.status(status).body(
            ErrorResponse(
                error = message,
                timestamp = Instant.now().toString()
            )
        )
    }

    /** 업스트림 응답을 ResponseEntity로 변환한다. */
    fun toResponseEntity(
        statusCode: HttpStatusCode,
        body: String
    ): ResponseEntity<Any> {
        if (statusCode == HttpStatus.NO_CONTENT || body.isBlank()) {
            return ResponseEntity.status(statusCode).build()
        }
        return ResponseEntity.status(statusCode)
            .body(parseJsonOrString(body))
    }

    /** HMAC 서명 헤더를 적용한다. 비밀 키가 없으면 무시한다. */
    fun applyHmacHeaders(
        headers: HttpHeaders,
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

    /**
     * 프록시 호출 메트릭을 기록하고 결과를 로깅한다.
     *
     * @param metricPrefix 메트릭 이름 접두사 (예: "arc.reactor.mcp.preflight")
     * @param logPrefix 로그 메시지 접두사 (예: "MCP preflight proxy")
     */
    fun observeProxyCall(
        registry: MeterRegistry?,
        metricPrefix: String,
        logPrefix: String,
        serverName: String,
        statusCode: Int,
        startedAtNanos: Long,
        extraTags: Array<String> = emptyArray()
    ) {
        val durationNanos = (System.nanoTime() - startedAtNanos).coerceAtLeast(0)
        val tags = buildMetricTags(statusCode, extraTags)
        recordMetrics(registry, metricPrefix, tags, durationNanos)
        logProxyResult(logPrefix, serverName, statusCode, durationNanos)
    }

    private fun buildMetricTags(statusCode: Int, extraTags: Array<String>): Array<String> {
        val base = arrayOf("status", statusCode.toString(), "outcome", resolveOutcome(statusCode))
        return if (extraTags.isEmpty()) base else base + extraTags
    }

    private fun recordMetrics(registry: MeterRegistry?, prefix: String, tags: Array<String>, durationNanos: Long) {
        registry?.counter("$prefix.proxy.requests", *tags)?.increment()
        registry?.let { r ->
            Timer.builder("$prefix.proxy.duration").tags(*tags).register(r).record(durationNanos, TimeUnit.NANOSECONDS)
        }
    }

    private fun logProxyResult(logPrefix: String, serverName: String, statusCode: Int, durationNanos: Long) {
        val durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos)
        if (statusCode >= 400) {
            proxyLogger.warn { "$logPrefix failed: server=$serverName status=$statusCode durationMs=$durationMs" }
        } else {
            proxyLogger.debug { "$logPrefix succeeded: server=$serverName status=$statusCode durationMs=$durationMs" }
        }
    }

    /** 요청 본문을 JSON 문자열로 직렬화한다. null이면 빈 객체를 반환한다. */
    fun serializeBody(body: Any?): String {
        return objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())
    }

    private fun resolveOutcome(statusCode: Int): String = when {
        statusCode in 200..299 -> "success"
        statusCode in 400..499 -> "client_error"
        else -> "server_error"
    }
}
