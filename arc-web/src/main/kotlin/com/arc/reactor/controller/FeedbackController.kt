package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.feedback.Feedback
import com.arc.reactor.feedback.FeedbackRating
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.hook.impl.FeedbackMetadataCaptureHook
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.swagger.v3.oas.annotations.Operation
import mu.KotlinLogging
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import java.time.Instant
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

/**
 * 피드백 API 컨트롤러.
 *
 * 에이전트 응답에 대한 사용자 피드백을 수집하고 내보내는 REST API를 제공합니다.
 * 피드백 데이터를 eval-testing 스키마 형식으로 내보내 오프라인 평가에 활용할 수 있습니다.
 *
 * ## 엔드포인트
 * - POST   /api/feedback          : 피드백 제출 (모든 사용자, runId로 자동 보강)
 * - GET    /api/feedback          : 필터 기반 피드백 목록 조회 (관리자)
 * - GET    /api/feedback/export   : eval-testing 스키마 형식으로 내보내기 (관리자)
 * - GET    /api/feedback/{id}     : 단일 피드백 조회 (모든 사용자)
 * - DELETE /api/feedback/{id}     : 피드백 삭제 (관리자)
 *
 * @see FeedbackStore
 * @see FeedbackMetadataCaptureHook
 */
@Tag(name = "Feedback", description = "User feedback collection and export")
@RestController
@RequestMapping("/api/feedback")
@ConditionalOnProperty(
    prefix = "arc.reactor.feedback", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class FeedbackController(
    private val feedbackStore: FeedbackStore,
    private val metadataCaptureHook: FeedbackMetadataCaptureHook
) {

    /**
     * 피드백을 제출한다. runId가 제공되면 실행 메타데이터로 자동 보강한다.
     *
     * 보강 우선순위: 요청에 명시된 값 > 캐시된 메타데이터 > 빈 기본값.
     */
    @Operation(summary = "에이전트 응답에 대한 피드백 제출")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Feedback submitted"),
        ApiResponse(responseCode = "400", description = "Invalid rating value")
    ])
    @PostMapping
    fun submitFeedback(
        @Valid @RequestBody request: SubmitFeedbackRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<FeedbackResponse> {
        val rating = parseRating(request.rating)
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String

        val metadata = request.runId?.let { metadataCaptureHook.get(it) }

        logger.info { "audit category=feedback action=SUBMIT actor=${userId ?: "anonymous"} sessionId=${request.sessionId}" }

        val feedback = Feedback(
            query = enrichString(request.query, metadata?.userPrompt),
            response = enrichString(request.response, metadata?.agentResponse),
            rating = rating,
            comment = request.comment,
            sessionId = request.sessionId ?: metadata?.sessionId,
            runId = request.runId,
            userId = userId,
            intent = request.intent,
            domain = request.domain,
            model = request.model,
            promptVersion = request.promptVersion,
            toolsUsed = request.toolsUsed ?: metadata?.toolsUsed,
            durationMs = request.durationMs ?: metadata?.durationMs,
            tags = request.tags,
            templateId = request.templateId ?: metadata?.templateId
        )

        val saved = feedbackStore.save(feedback)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    /** 선택적 필터로 피드백 목록을 조회한다. 관리자 권한 필요. */
    @Operation(summary = "필터 기반 피드백 목록 조회 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Paginated list of feedback entries"),
        ApiResponse(responseCode = "400", description = "Invalid filter parameters"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun listFeedback(
        @RequestParam(required = false) rating: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) intent: String?,
        @RequestParam(required = false) sessionId: String?,
        @RequestParam(required = false) templateId: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
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
            sessionId = sessionId,
            templateId = templateId
        )
        val clamped = clampLimit(limit)
        return ResponseEntity.ok(results.map { it.toResponse() }.paginate(offset, clamped))
    }

    /** eval-testing 스키마 형식으로 피드백을 내보낸다. 관리자 권한 필요. */
    @Operation(summary = "eval-testing 형식으로 피드백 내보내기 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Exported feedback data"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
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

    /** ID로 단일 피드백 항목을 조회한다. */
    @Operation(summary = "ID로 피드백 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Feedback entry"),
        ApiResponse(responseCode = "404", description = "Feedback not found")
    ])
    @GetMapping("/{feedbackId}")
    fun getFeedback(@PathVariable feedbackId: String): ResponseEntity<Any> {
        val feedback = feedbackStore.get(feedbackId)
            ?: return notFoundResponse("Feedback not found: $feedbackId")
        return ResponseEntity.ok(feedback.toResponse())
    }

    /** 피드백 항목을 삭제한다. 관리자 권한 필요. */
    @Operation(summary = "피드백 삭제 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Feedback deleted"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
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

/** 명시적 값이 비어있지 않으면 사용하고, 아니면 메타데이터 값으로 대체하고, 그것도 없으면 빈 문자열을 반환한다. */
private fun enrichString(explicit: String?, fallback: String?): String {
    return explicit.takeUnless { it.isNullOrBlank() } ?: fallback.orEmpty()
}

// --- 요청 DTO ---

data class SubmitFeedbackRequest(
    @field:NotBlank val rating: String,
    @field:Size(max = 10000) val query: String? = null,
    @field:Size(max = 50000) val response: String? = null,
    @field:Size(max = 5000) val comment: String? = null,
    @field:Size(max = 120) val sessionId: String? = null,
    @field:Size(max = 120) val runId: String? = null,
    @field:Size(max = 120) val intent: String? = null,
    @field:Size(max = 120) val domain: String? = null,
    @field:Size(max = 120) val model: String? = null,
    val promptVersion: Int? = null,
    @field:Size(max = 50) val toolsUsed: List<String>? = null,
    val durationMs: Long? = null,
    @field:Size(max = 20) val tags: List<String>? = null,
    @field:Size(max = 120) val templateId: String? = null
)

// --- 응답 DTO ---

data class FeedbackResponse(
    val feedbackId: String,
    val query: String,
    val response: String,
    val rating: String,
    val timestamp: String,
    val comment: String?,
    val runId: String?,
    val intent: String?,
    val domain: String?,
    val model: String?,
    val promptVersion: Int?,
    val toolsUsed: List<String>?,
    val durationMs: Long?,
    val tags: List<String>?,
    val templateId: String?
)

// --- 매핑 확장 ---

private fun Feedback.toResponse() = FeedbackResponse(
    feedbackId = feedbackId,
    query = query,
    response = response,
    rating = rating.name.lowercase(),
    timestamp = timestamp.toString(),
    comment = comment,
    runId = runId,
    intent = intent,
    domain = domain,
    model = model,
    promptVersion = promptVersion,
    toolsUsed = toolsUsed,
    durationMs = durationMs,
    tags = tags,
    templateId = templateId
)

private fun Feedback.toExportItem() = mapOf(
    "feedbackId" to feedbackId,
    "query" to query,
    "response" to response,
    "rating" to rating.name.lowercase(),
    "timestamp" to timestamp.toString(),
    "comment" to comment,
    "runId" to runId,
    "intent" to intent,
    "domain" to domain,
    "model" to model,
    "promptVersion" to promptVersion,
    "toolsUsed" to toolsUsed,
    "durationMs" to durationMs,
    "tags" to tags,
    "templateId" to templateId
)
