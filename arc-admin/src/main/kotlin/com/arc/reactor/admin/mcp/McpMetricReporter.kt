package com.arc.reactor.admin.mcp

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
 * Lightweight metric reporter for MCP servers.
 *
 * MCP servers add this as a dependency and call [reportToolCall] / [reportHealth]
 * to push metrics to the arc-admin metric ingestion endpoint.
 *
 * Features:
 * - Async: events are buffered and flushed in batch to avoid blocking MCP tool execution
 * - Resilient: failures are logged, not thrown — never disrupts MCP server operation
 * - Configurable: endpoint URL, flush interval, batch size
 *
 * Usage:
 * ```kotlin
 * val reporter = McpMetricReporter(
 *     endpoint = "http://localhost:8080/api/admin/metrics/ingest",
 *     tenantId = "tenant-1",
 *     serverName = "error-log-mcp"
 * )
 * reporter.start()
 *
 * // In tool handler:
 * reporter.reportToolCall("analyze_error", durationMs = 250, success = true, runId = "run-xyz")
 *
 * // On shutdown:
 * reporter.stop()
 * ```
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

    /**
     * Report a tool call execution from this MCP server.
     */
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

    /**
     * Report this MCP server's health status.
     */
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

        // Group by type and send
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
            val json = toJson(body)
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

    /**
     * Minimal JSON serialization without Jackson dependency.
     * MCP servers may not have Jackson on classpath.
     */
    private fun toJson(map: Map<String, Any?>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            "\"${escapeJson(k)}\":${valueToJson(v)}"
        }
        return "{$entries}"
    }

    private fun valueToJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escapeJson(value)}\""
        is Boolean, is Number -> value.toString()
        else -> "\"${escapeJson(value.toString())}\""
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}

private data class MetricPayload(
    val type: String,
    val data: Map<String, Any?>
)
