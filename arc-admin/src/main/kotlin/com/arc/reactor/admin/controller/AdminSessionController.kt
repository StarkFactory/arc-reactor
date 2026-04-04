package com.arc.reactor.admin.controller

import com.arc.reactor.admin.query.AdminSessionQueryService
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.recordAdminAudit
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import mu.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
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
import java.time.Instant

private val logger = KotlinLogging.logger {}

private val UNSAFE_FILENAME_CHARS = Regex("[^a-zA-Z0-9._-]")
private const val MAX_FILENAME_LENGTH = 100

/**
 * 어드민 세션 관리 API.
 *
 * 모든 엔드포인트는 ADMIN 이상 권한이 필요하며, 유저 소유권 검증 없이
 * 플랫폼 전체 세션을 조회/관리한다.
 */
@Tag(name = "Admin Sessions", description = "어드민 세션 관리")
@RestController
@ConditionalOnProperty(
    prefix = "arc.reactor.admin", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@RequestMapping("/api/admin")
class AdminSessionController(
    private val queryService: AdminSessionQueryService,
    private val auditStoreProvider: ObjectProvider<AdminAuditStore>
) {

    // ── 1. 통계 조회 ───────────────────────────────────────

    @Operation(summary = "대화 Overview 통계 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Overview 통계"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    )
    @GetMapping("/sessions/overview")
    fun getOverview(
        @RequestParam(defaultValue = "7d") period: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()

        val days = parsePeriodDays(period)
        val overview = queryService.getOverview(days)
        return ResponseEntity.ok(overview)
    }

    // ── 2. 세션 목록 ───────────────────────────────────────

    @Operation(summary = "전체 세션 목록 조회 (필터/페이지네이션)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "세션 목록"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    )
    @GetMapping("/sessions")
    fun listSessions(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) channel: List<String>?,
        @RequestParam(required = false) personaId: String?,
        @RequestParam(required = false) trust: List<String>?,
        @RequestParam(required = false) feedback: List<String>?,
        @RequestParam(required = false) dateFrom: Long?,
        @RequestParam(required = false) dateTo: Long?,
        @RequestParam(defaultValue = "lastActivity") sortBy: String,
        @RequestParam(defaultValue = "desc") order: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "30") limit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()

        val filters = SessionQueryFilters(
            q = q, channel = channel, personaId = personaId,
            trust = trust, feedback = feedback,
            dateFrom = dateFrom, dateTo = dateTo,
            sortBy = sortBy, order = order
        )
        val result = queryService.listSessions(filters, limit, offset)
        return ResponseEntity.ok(result)
    }

    // ── 3. 세션 상세 ───────────────────────────────────────

    @Operation(summary = "세션 상세 조회 (메시지 포함)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "세션 상세"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "세션 없음")
    )
    @GetMapping("/sessions/{sessionId}")
    fun getSessionDetail(
        @PathVariable sessionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()

        val detail = queryService.getSessionDetail(sessionId)
            ?: return notFoundResponse("Session not found")
        return ResponseEntity.ok(detail)
    }

    // ── 4. 세션 삭제 ───────────────────────────────────────

    @Operation(summary = "세션 삭제")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 완료"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "세션 없음")
    )
    @DeleteMapping("/sessions/{sessionId}")
    fun deleteSession(
        @PathVariable sessionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val actor = currentActor(exchange)
        val deleted = queryService.deleteSession(sessionId)
        if (!deleted) return notFoundResponse("Session not found")

        logger.info { "audit category=session action=DELETE actor=$actor resourceId=$sessionId" }
        auditStoreProvider.ifAvailable?.let { store ->
            recordAdminAudit(
                store = store, category = "session", action = "DELETE",
                actor = actor, resourceType = "session", resourceId = sessionId
            )
        }

        return ResponseEntity.noContent().build()
    }

    // ── 5. 세션 내보내기 ───────────────────────────────────

    @Operation(summary = "세션 내보내기 (JSON/Markdown)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "파일 다운로드"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "세션 없음")
    )
    @GetMapping("/sessions/{sessionId}/export")
    fun exportSession(
        @PathVariable sessionId: String,
        @RequestParam(defaultValue = "json") format: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val messages = queryService.getSessionMessages(sessionId)
            ?: return notFoundResponse("Session not found")

        val safeId = sanitizeFilename(sessionId)

        return when (format.lowercase()) {
            "markdown", "md" -> {
                val sb = StringBuilder()
                sb.appendLine("# Session: $sessionId")
                sb.appendLine("Exported at: ${Instant.now()}")
                sb.appendLine()
                for (msg in messages) {
                    sb.appendLine("## ${msg.role}")
                    sb.appendLine()
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$safeId.md\"")
                    .contentType(MediaType.TEXT_MARKDOWN)
                    .body(sb.toString())
            }
            else -> {
                val body = mapOf(
                    "sessionId" to sessionId,
                    "exportedAt" to Instant.now().toEpochMilli(),
                    "messages" to messages.map { msg ->
                        mapOf(
                            "role" to msg.role,
                            "content" to msg.content,
                            "timestamp" to msg.timestamp
                        )
                    }
                )
                ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$safeId.json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
            }
        }
    }

    // ── 6. 유저 목록 ───────────────────────────────────────

    @Operation(summary = "유저 목록 (세션 활동 요약)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "유저 목록"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    )
    @GetMapping("/users")
    fun listUsers(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "lastActive") sortBy: String,
        @RequestParam(required = false) period: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "30") limit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()

        val periodDays = period?.let { parsePeriodDays(it) }
        val filters = UserQueryFilters(q = q, sortBy = sortBy, periodDays = periodDays)
        val result = queryService.listUsers(filters, limit, offset)
        return ResponseEntity.ok(result)
    }

    // ── 7. 유저별 세션 ─────────────────────────────────────

    @Operation(summary = "특정 유저의 세션 목록")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "유저 세션 목록"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    )
    @GetMapping("/users/{userId}/sessions")
    fun listUserSessions(
        @PathVariable userId: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) channel: List<String>?,
        @RequestParam(required = false) personaId: String?,
        @RequestParam(required = false) trust: List<String>?,
        @RequestParam(required = false) feedback: List<String>?,
        @RequestParam(required = false) dateFrom: Long?,
        @RequestParam(required = false) dateTo: Long?,
        @RequestParam(defaultValue = "lastActivity") sortBy: String,
        @RequestParam(defaultValue = "desc") order: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "30") limit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()

        val filters = SessionQueryFilters(
            q = q, userId = userId, channel = channel, personaId = personaId,
            trust = trust, feedback = feedback,
            dateFrom = dateFrom, dateTo = dateTo,
            sortBy = sortBy, order = order
        )
        val result = queryService.listSessions(filters, limit, offset)
        return ResponseEntity.ok(result)
    }

    // ── 8. 태그 추가 ───────────────────────────────────────

    @Operation(summary = "세션에 태그 추가")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "태그 생성됨"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    )
    @PostMapping("/sessions/{sessionId}/tags")
    fun addTag(
        @PathVariable sessionId: String,
        @RequestBody @jakarta.validation.Valid request: AddTagRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        if (request.label.isBlank()) return badRequestResponse("label is required")

        val actor = currentActor(exchange)
        val tag = queryService.addTag(sessionId, request.label.trim(), request.comment?.trim(), actor)
        return ResponseEntity.ok(tag)
    }

    // ── 9. 태그 삭제 ───────────────────────────────────────

    @Operation(summary = "세션 태그 삭제")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "태그 삭제됨"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "태그 없음")
    )
    @DeleteMapping("/sessions/{sessionId}/tags/{tagId}")
    fun removeTag(
        @PathVariable sessionId: String,
        @PathVariable tagId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val removed = queryService.removeTag(sessionId, tagId)
        if (!removed) return notFoundResponse("Tag not found")

        return ResponseEntity.noContent().build()
    }

    // ── 헬퍼 ───────────────────────────────────────────────

    private fun parsePeriodDays(period: String): Int = when (period) {
        "1d" -> 1
        "7d" -> 7
        "30d" -> 30
        "90d" -> 90
        else -> 7
    }

    private fun sanitizeFilename(name: String): String =
        name.replace(UNSAFE_FILENAME_CHARS, "_").take(MAX_FILENAME_LENGTH)
}
