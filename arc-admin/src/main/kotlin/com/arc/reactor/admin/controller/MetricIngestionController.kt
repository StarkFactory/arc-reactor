package com.arc.reactor.admin.controller

import com.arc.reactor.admin.collection.MetricRingBuffer
import com.arc.reactor.admin.model.EvalResultEvent
import com.arc.reactor.admin.model.McpHealthEvent
import com.arc.reactor.admin.model.ToolCallEvent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.math.BigDecimal
import java.time.Instant

@Tag(name = "Metric Ingestion", description = "Metric ingestion endpoint for MCP servers and external sources")
@RestController
@RequestMapping("/api/admin/metrics/ingest")
class MetricIngestionController(
    private val ringBuffer: MetricRingBuffer
) {

    companion object {
        const val MAX_BATCH_SIZE = 1000
    }

    @Operation(summary = "Ingest MCP health event")
    @PostMapping("/mcp-health")
    fun ingestMcpHealth(@RequestBody request: McpHealthRequest, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val event = McpHealthEvent(
            time = Instant.now(),
            tenantId = request.tenantId,
            serverName = request.serverName,
            status = request.status,
            responseTimeMs = request.responseTimeMs,
            errorClass = request.errorClass,
            errorMessage = request.errorMessage,
            toolCount = request.toolCount
        )
        val accepted = ringBuffer.publish(event)
        return if (accepted) {
            ResponseEntity.accepted().body(mapOf("status" to "accepted"))
        } else {
            ResponseEntity.status(503).body(
                AdminErrorResponse(error = "Metric buffer full, event dropped")
            )
        }
    }

    @Operation(summary = "Ingest MCP tool call event")
    @PostMapping("/tool-call")
    fun ingestToolCall(@RequestBody request: ToolCallRequest, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val event = ToolCallEvent(
            time = Instant.now(),
            tenantId = request.tenantId,
            runId = request.runId,
            toolName = request.toolName,
            toolSource = request.toolSource ?: "mcp",
            mcpServerName = request.mcpServerName,
            callIndex = request.callIndex ?: 0,
            success = request.success,
            durationMs = request.durationMs,
            errorClass = request.errorClass,
            errorMessage = request.errorMessage
        )
        val accepted = ringBuffer.publish(event)
        return if (accepted) {
            ResponseEntity.accepted().body(mapOf("status" to "accepted"))
        } else {
            ResponseEntity.status(503).body(
                AdminErrorResponse(error = "Metric buffer full, event dropped")
            )
        }
    }

    @Operation(summary = "Ingest eval result event")
    @PostMapping("/eval-result")
    fun ingestEvalResult(@RequestBody request: EvalResultRequest, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val event = EvalResultEvent(
            time = Instant.now(),
            tenantId = request.tenantId,
            evalRunId = request.evalRunId,
            testCaseId = request.testCaseId,
            pass = request.pass,
            score = request.score,
            latencyMs = request.latencyMs,
            tokenUsage = request.tokenUsage,
            cost = request.cost ?: BigDecimal.ZERO,
            assertionType = request.assertionType,
            failureClass = request.failureClass,
            failureDetail = request.failureDetail?.take(500),
            tags = request.tags ?: emptyList()
        )
        val accepted = ringBuffer.publish(event)
        return if (accepted) {
            ResponseEntity.accepted().body(mapOf("status" to "accepted"))
        } else {
            ResponseEntity.status(503).body(
                AdminErrorResponse(error = "Metric buffer full, event dropped")
            )
        }
    }

    @Operation(summary = "Batch ingest eval results from a single eval run")
    @PostMapping("/eval-results")
    fun ingestEvalResults(@RequestBody request: EvalRunResultsRequest, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        if (request.results.size > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().body(
                AdminErrorResponse(error = "Batch size exceeds limit of $MAX_BATCH_SIZE")
            )
        }
        if (request.results.isEmpty()) {
            return ResponseEntity.badRequest().body(
                AdminErrorResponse(error = "Results list must not be empty")
            )
        }
        var accepted = 0
        var dropped = 0
        for (result in request.results) {
            val event = EvalResultEvent(
                time = Instant.now(),
                tenantId = request.tenantId,
                evalRunId = request.evalRunId,
                testCaseId = result.testCaseId,
                pass = result.pass,
                score = result.score,
                latencyMs = result.latencyMs,
                tokenUsage = result.tokenUsage,
                cost = result.cost ?: BigDecimal.ZERO,
                assertionType = result.assertionType,
                failureClass = result.failureClass,
                failureDetail = result.failureDetail?.take(500),
                tags = result.tags ?: emptyList()
            )
            if (ringBuffer.publish(event)) accepted++ else dropped++
        }
        return ResponseEntity.ok(
            mapOf("evalRunId" to request.evalRunId, "accepted" to accepted, "dropped" to dropped)
        )
    }

    @Operation(summary = "Batch ingest multiple MCP health events")
    @PostMapping("/batch")
    fun ingestBatch(@RequestBody requests: List<McpHealthRequest>, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        if (requests.size > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().body(
                AdminErrorResponse(error = "Batch size exceeds limit of $MAX_BATCH_SIZE")
            )
        }
        var accepted = 0
        var dropped = 0
        for (request in requests) {
            val event = McpHealthEvent(
                time = Instant.now(),
                tenantId = request.tenantId,
                serverName = request.serverName,
                status = request.status,
                responseTimeMs = request.responseTimeMs,
                errorClass = request.errorClass,
                errorMessage = request.errorMessage,
                toolCount = request.toolCount
            )
            if (ringBuffer.publish(event)) accepted++ else dropped++
        }
        return ResponseEntity.ok(mapOf("accepted" to accepted, "dropped" to dropped))
    }
}

data class McpHealthRequest(
    val tenantId: String,
    val serverName: String,
    val status: String = "CONNECTED",
    val responseTimeMs: Long = 0,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val toolCount: Int = 0
)

data class ToolCallRequest(
    val tenantId: String,
    val runId: String,
    val toolName: String,
    val toolSource: String? = "mcp",
    val mcpServerName: String? = null,
    val callIndex: Int? = 0,
    val success: Boolean = true,
    val durationMs: Long = 0,
    val errorClass: String? = null,
    val errorMessage: String? = null
)

data class EvalResultRequest(
    val tenantId: String,
    val evalRunId: String,
    val testCaseId: String,
    val pass: Boolean,
    val score: Double = 0.0,
    val latencyMs: Long = 0,
    val tokenUsage: Int = 0,
    val cost: BigDecimal? = null,
    val assertionType: String? = null,
    val failureClass: String? = null,
    val failureDetail: String? = null,
    val tags: List<String>? = null
)

data class EvalRunResultsRequest(
    val tenantId: String,
    val evalRunId: String,
    val results: List<EvalTestCaseResult>
)

data class EvalTestCaseResult(
    val testCaseId: String,
    val pass: Boolean,
    val score: Double = 0.0,
    val latencyMs: Long = 0,
    val tokenUsage: Int = 0,
    val cost: BigDecimal? = null,
    val assertionType: String? = null,
    val failureClass: String? = null,
    val failureDetail: String? = null,
    val tags: List<String>? = null
)
