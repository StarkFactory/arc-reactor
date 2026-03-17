package com.arc.reactor.admin.mcp

import com.arc.reactor.admin.JsonEscaper
import mu.KotlinLogging
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * MCP 서버용 경량 메트릭 리포터.
 *
 * MCP 서버가 의존성으로 추가한 뒤 [reportToolCall] / [reportHealth]를 호출하여
 * arc-admin 메트릭 수집 엔드포인트로 메트릭을 푸시한다.
 *
 * 특징:
 * - 비동기: 이벤트를 버퍼링하고 배치로 flush하여 MCP 도구 실행을 블로킹하지 않음
 * - 탄력적: 실패는 로깅만 하고 예외를 던지지 않음 — MCP 서버 운영에 영향 없음
 * - 설정 가능: 엔드포인트 URL, flush 주기, 배치 크기
 *
 * 사용 예:
 * ```kotlin
 * val reporter = McpMetricReporter(
 *     endpoint = "http://localhost:8080/api/admin/metrics/ingest",
 *     tenantId = "tenant-1",
 *     serverName = "error-log-mcp"
 * )
 * reporter.start()
 *
 * // 도구 핸들러에서:
 * reporter.reportToolCall("analyze_error", durationMs = 250, success = true, runId = "run-xyz")
 *
 * // 종료 시:
 * reporter.stop()
 * ```
 *
 * @see MetricIngestionController 메트릭 수집 API
 */
class McpMetricReporter(
    private val endpoint: String,
    private val tenantId: String,
    private val serverName: String,
    private val flushIntervalMs: Long = 5000,
    private val maxBatchSize: Int = 100,
    private val maxQueueSize: Int = 10000,
    private val connectTimeoutMs: Long = 5000,
    private val requestTimeoutMs: Long = 10000
) {

    private val queue = ConcurrentLinkedQueue<MetricPayload>()
    private val running = AtomicBoolean(false)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mcp-metric-reporter-$serverName").apply { isDaemon = true }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        scheduler.scheduleWithFixedDelay(
            { flush() },
            flushIntervalMs,
            flushIntervalMs,
            TimeUnit.MILLISECONDS
        )
        logger.info { "McpMetricReporter started for $serverName -> $endpoint" }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        flush() // drain remaining
        scheduler.shutdown()
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        logger.info { "McpMetricReporter stopped for $serverName" }
    }

    /** 이 MCP 서버에서의 도구 호출 실행을 보고한다. */
    fun reportToolCall(
        toolName: String,
        durationMs: Long,
        success: Boolean,
        runId: String = "",
        errorClass: String? = null,
        errorMessage: String? = null
    ) {
        enqueue(
            MetricPayload(
                type = "tool_call",
                data = mapOf(
                    "tenantId" to tenantId,
                    "runId" to runId,
                    "toolName" to toolName,
                    "toolSource" to "mcp",
                    "mcpServerName" to serverName,
                    "success" to success,
                    "durationMs" to durationMs,
                    "errorClass" to errorClass,
                    "errorMessage" to errorMessage?.take(500)
                ).filterValues { it != null }
            )
        )
    }

    /** 이 MCP 서버의 상태를 보고한다. */
    fun reportHealth(
        status: String = "CONNECTED",
        responseTimeMs: Long = 0,
        toolCount: Int = 0,
        errorClass: String? = null,
        errorMessage: String? = null
    ) {
        enqueue(
            MetricPayload(
                type = "mcp_health",
                data = mapOf(
                    "tenantId" to tenantId,
                    "serverName" to serverName,
                    "status" to status,
                    "responseTimeMs" to responseTimeMs,
                    "toolCount" to toolCount,
                    "errorClass" to errorClass,
                    "errorMessage" to errorMessage?.take(500)
                ).filterValues { it != null }
            )
        )
    }

    private fun enqueue(payload: MetricPayload) {
        if (queue.size >= maxQueueSize) {
            logger.debug { "McpMetricReporter queue full ($maxQueueSize), dropping event" }
            return
        }
        queue.offer(payload)
    }

    private fun flush() {
        if (queue.isEmpty()) return

        val batch = mutableListOf<MetricPayload>()
        while (batch.size < maxBatchSize) {
            val item = queue.poll() ?: break
            batch.add(item)
        }
        if (batch.isEmpty()) return

        // ── 단계: 타입별 그룹화 후 전송 ──
        val toolCalls = batch.filter { it.type == "tool_call" }
        val healthEvents = batch.filter { it.type == "mcp_health" }

        for (event in toolCalls) {
            sendPost("$endpoint/tool-call", event.data)
        }
        for (event in healthEvents) {
            sendPost("$endpoint/mcp-health", event.data)
        }
    }

    private fun sendPost(url: String, body: Map<String, Any?>) {
        try {
            val json = JsonEscaper.mapToJson(body)
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.debug { "McpMetricReporter POST $url returned ${response.statusCode()}" }
            }
        } catch (e: IOException) {
            logger.debug { "McpMetricReporter failed to send to $url: ${e.message}" }
        } catch (e: Exception) {
            logger.debug(e) { "McpMetricReporter unexpected error sending to $url" }
        }
    }

}

/** 메트릭 페이로드. 타입(tool_call, mcp_health)과 데이터를 포함한다. */
private data class MetricPayload(
    val type: String,
    val data: Map<String, Any?>
)
