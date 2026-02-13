package com.arc.reactor.errorreport.controller

import com.arc.reactor.errorreport.config.ErrorReportProperties
import com.arc.reactor.errorreport.handler.ErrorReportHandler
import com.arc.reactor.errorreport.model.ErrorReportRequest
import com.arc.reactor.errorreport.model.ErrorReportResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Receives error reports from production servers and triggers autonomous AI analysis.
 *
 * Processing is asynchronous -- the endpoint returns 200 immediately.
 * The AI agent analyzes the error using MCP tools and sends the report to Slack.
 */
@RestController
@RequestMapping("/api/error-report")
@ConditionalOnProperty(prefix = "arc.reactor.error-report", name = ["enabled"], havingValue = "true")
@Tag(name = "Error Report", description = "Production error report ingestion")
class ErrorReportController(
    private val handler: ErrorReportHandler,
    private val properties: ErrorReportProperties
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val semaphore = Semaphore(properties.maxConcurrentRequests)

    @PostMapping
    @Operation(summary = "Submit a production error report for AI-powered analysis")
    suspend fun report(
        @Valid @RequestBody request: ErrorReportRequest,
        @RequestHeader(value = "X-API-Key", required = false) apiKey: String?
    ): ResponseEntity<Any> {
        if (!validateApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("error" to "Invalid or missing API key"))
        }

        val requestId = UUID.randomUUID().toString()

        val truncatedRequest = if (request.stackTrace.length > properties.maxStackTraceLength) {
            request.copy(stackTrace = request.stackTrace.take(properties.maxStackTraceLength) + "\n... [truncated]")
        } else {
            request
        }

        processAsync(requestId, truncatedRequest)

        return ResponseEntity.ok(ErrorReportResponse(accepted = true, requestId = requestId))
    }

    private fun processAsync(requestId: String, request: ErrorReportRequest) {
        scope.launch {
            semaphore.withPermit {
                try {
                    handler.handle(requestId, request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled error in error report processing requestId=$requestId" }
                }
            }
        }
    }

    private fun validateApiKey(headerKey: String?): Boolean {
        if (properties.apiKey.isBlank()) return true
        return headerKey == properties.apiKey
    }
}
