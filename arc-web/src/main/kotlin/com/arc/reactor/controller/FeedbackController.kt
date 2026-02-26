package com.arc.reactor.controller

import com.arc.reactor.feedback.Feedback
import com.arc.reactor.feedback.FeedbackRating
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.hook.impl.FeedbackMetadataCaptureHook
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Feedback API Controller
 *
 * Provides REST APIs for collecting and exporting user feedback on agent responses.
 * Feedback data can be exported in eval-testing schema format for offline evaluation.
 *
 * ## Endpoints
 * - POST   /api/feedback          : Submit feedback (any user, auto-enriches with runId)
 * - GET    /api/feedback          : List feedback with filters (Admin)
 * - GET    /api/feedback/export   : Export in eval-testing schema format (Admin)
 * - GET    /api/feedback/{id}     : Get single feedback (any user)
 * - DELETE /api/feedback/{id}     : Delete feedback (Admin)
 */
@Tag(name = "Feedback", description = "User feedback collection and export")
@RestController
@RequestMapping("/api/feedback")
@ConditionalOnBean(FeedbackStore::class)
class FeedbackController(
    private val feedbackStore: FeedbackStore,
    private val metadataCaptureHook: FeedbackMetadataCaptureHook
) {

    /**
     * Submit feedback. If runId is provided, auto-enriches with execution metadata.
     *
     * Enrichment priority: explicit request values > cached metadata > empty defaults.
     */
    @Operation(summary = "Submit feedback on an agent response")
    @PostMapping
    fun submitFeedback(
        @RequestBody request: SubmitFeedbackRequest
    ): ResponseEntity<FeedbackResponse> {
        val rating = parseRating(request.rating)

        val metadata = request.runId?.let { metadataCaptureHook.get(it) }

        val feedback = Feedback(
            query = enrichString(request.query, metadata?.userPrompt),
            response = enrichString(request.response, metadata?.agentResponse),
            rating = rating,
            comment = request.comment,
            sessionId = request.sessionId ?: metadata?.sessionId,
            runId = request.runId,
            userId = request.userId ?: metadata?.userId,
            intent = request.intent,
            domain = request.domain,
            model = request.model,
            promptVersion = request.promptVersion,
            toolsUsed = request.toolsUsed ?: metadata?.toolsUsed,
            durationMs = request.durationMs ?: metadata?.durationMs,
            tags = request.tags
        )

        val saved = feedbackStore.save(feedback)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    /**
     * List feedback with optional filters. Requires Admin.
     */
    @Operation(summary = "List feedback with filters (Admin)")
    @GetMapping
    fun listFeedback(
        @RequestParam(required = false) rating: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) intent: String?,
        @RequestParam(required = false) sessionId: String?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val ratingEnum = rating?.let(::parseRating)
        val fromInstant = from?.let { parseInstant("from", it) }
        val toInstant = to?.let { parseInstant("to", it) }

        val results = feedbackStore.list(
            rating = ratingEnum,
            from = fromInstant,
            to = toInstant,
            intent = intent,
            sessionId = sessionId
        )
        return ResponseEntity.ok(results.map { it.toResponse() })
    }

    /**
     * Export feedback in eval-testing schema format. Requires Admin.
     */
    @Operation(summary = "Export feedback in eval-testing format (Admin)")
    @GetMapping("/export")
    fun exportFeedback(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val items = feedbackStore.list().map { it.toExportItem() }
        val export = mapOf(
            "version" to 1,
            "exportedAt" to Instant.now().toString(),
            "source" to "arc-reactor",
            "items" to items
        )
        return ResponseEntity.ok(export)
    }

    /**
     * Get a single feedback entry by ID.
     */
    @Operation(summary = "Get feedback by ID")
    @GetMapping("/{feedbackId}")
    fun getFeedback(@PathVariable feedbackId: String): ResponseEntity<FeedbackResponse> {
        val feedback = feedbackStore.get(feedbackId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(feedback.toResponse())
    }

    /**
     * Delete a feedback entry. Requires Admin.
     */
    @Operation(summary = "Delete feedback (Admin)")
    @DeleteMapping("/{feedbackId}")
    fun deleteFeedback(
        @PathVariable feedbackId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        feedbackStore.delete(feedbackId)
        return ResponseEntity.noContent().build()
    }

    private fun parseRating(raw: String): FeedbackRating {
        return try {
            FeedbackRating.valueOf(raw.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            throw ServerWebInputException("Invalid rating: $raw")
        }
    }

    private fun parseInstant(field: String, raw: String): Instant {
        return try {
            Instant.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            throw ServerWebInputException("Invalid timestamp for '$field': $raw")
        }
    }
}

/**
 * Returns explicit value if non-blank, otherwise falls back to metadata value, then empty string.
 */
private fun enrichString(explicit: String?, fallback: String?): String {
    return explicit.takeUnless { it.isNullOrBlank() } ?: fallback.orEmpty()
}

// --- Request DTO ---

data class SubmitFeedbackRequest(
    val rating: String,
    val query: String? = null,
    val response: String? = null,
    val comment: String? = null,
    val sessionId: String? = null,
    val runId: String? = null,
    val userId: String? = null,
    val intent: String? = null,
    val domain: String? = null,
    val model: String? = null,
    val promptVersion: Int? = null,
    val toolsUsed: List<String>? = null,
    val durationMs: Long? = null,
    val tags: List<String>? = null
)

// --- Response DTO ---

data class FeedbackResponse(
    val feedbackId: String,
    val query: String,
    val response: String,
    val rating: String,
    val timestamp: String,
    val comment: String?,
    val sessionId: String?,
    val runId: String?,
    val userId: String?,
    val intent: String?,
    val domain: String?,
    val model: String?,
    val promptVersion: Int?,
    val toolsUsed: List<String>?,
    val durationMs: Long?,
    val tags: List<String>?
)

// --- Mapping extensions ---

private fun Feedback.toResponse() = FeedbackResponse(
    feedbackId = feedbackId,
    query = query,
    response = response,
    rating = rating.name.lowercase(),
    timestamp = timestamp.toString(),
    comment = comment,
    sessionId = sessionId,
    runId = runId,
    userId = userId,
    intent = intent,
    domain = domain,
    model = model,
    promptVersion = promptVersion,
    toolsUsed = toolsUsed,
    durationMs = durationMs,
    tags = tags
)

private fun Feedback.toExportItem() = mapOf(
    "feedbackId" to feedbackId,
    "query" to query,
    "response" to response,
    "rating" to rating.name.lowercase(),
    "timestamp" to timestamp.toString(),
    "comment" to comment,
    "sessionId" to sessionId,
    "runId" to runId,
    "userId" to userId,
    "intent" to intent,
    "domain" to domain,
    "model" to model,
    "promptVersion" to promptVersion,
    "toolsUsed" to toolsUsed,
    "durationMs" to durationMs,
    "tags" to tags
)
