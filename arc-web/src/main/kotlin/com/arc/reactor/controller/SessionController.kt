package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import io.swagger.v3.oas.annotations.Operation
import mu.KotlinLogging
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.memory.SessionSummary
import com.arc.reactor.memory.summary.ConversationSummaryStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 세션 관리 및 모델 API 컨트롤러.
 *
 * 대화 세션 관리와 사용 가능한 LLM 프로바이더 조회를 위한 REST API를 제공합니다.
 *
 * ## 엔드포인트
 * - GET /api/sessions                          : 전체 세션 목록 조회
 * - GET /api/sessions/{id}                     : 세션 메시지 조회
 * - GET /api/sessions/{id}/export?format=json  : JSON으로 내보내기
 * - GET /api/sessions/{id}/export?format=markdown : Markdown으로 내보내기
 * - DELETE /api/sessions/{id}                  : 세션 삭제
 * - GET /api/models                            : 사용 가능한 LLM 프로바이더 목록
 *
 * WHY: 세션 소유권 검증은 deny-by-default 원칙을 따른다.
 * owner가 기록되어 있고 요청자와 일치해야만 접근을 허용한다.
 *
 * @see MemoryStore
 * @see ChatModelProvider
 */
@Tag(name = "Sessions", description = "Conversation session and LLM provider management")
@RestController
@RequestMapping("/api")
class SessionController(
    private val memoryStore: MemoryStore,
    private val chatModelProvider: ChatModelProvider,
    private val adminAuditStore: AdminAuditStore,
    summaryStoreProvider: ObjectProvider<ConversationSummaryStore>,
    conversationManagerProvider: ObjectProvider<ConversationManager>
) {

    private val conversationSummaryStore: ConversationSummaryStore? = summaryStoreProvider.ifAvailable
    private val conversationManager: ConversationManager? = conversationManagerProvider.ifAvailable

    /**
     * 요약 메타데이터와 함께 전체 세션 목록을 조회한다.
     * 인증된 userId로 필터링된다.
     */
    @Operation(summary = "전체 대화 세션 목록 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Paginated list of sessions"),
        ApiResponse(responseCode = "401", description = "Missing authenticated user context")
    ])
    @GetMapping("/sessions")
    fun listSessions(
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
        exchange: ServerWebExchange
    ): PaginatedResponse<SessionResponse> {
        val userId = authenticatedUserId(exchange)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user context")
        val sessions = memoryStore.listSessionsByUserId(userId)
        val clamped = clampLimit(limit)
        return sessions.map { it.toResponse() }.paginate(offset, clamped)
    }

    /**
     * 특정 세션의 모든 메시지를 조회한다.
     * 세션 소유권을 검증한다.
     */
    @Operation(summary = "특정 세션의 메시지 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Session messages"),
        ApiResponse(responseCode = "401", description = "Missing authenticated user context"),
        ApiResponse(responseCode = "403", description = "Access denied to session"),
        ApiResponse(responseCode = "404", description = "Session not found")
    ])
    @GetMapping("/sessions/{sessionId}")
    fun getSession(
        @PathVariable sessionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        val userId = authenticatedUserId(exchange) ?: return unauthorizedResponse()
        if (!isSessionOwner(sessionId, userId, exchange)) {
            return sessionForbidden()
        }

        val memory = memoryStore.get(sessionId)
            ?: return notFoundResponse("Session not found: $sessionId")
        val messages = memory.getHistory().map { msg ->
            MessageResponse(
                role = msg.role.name.lowercase(),
                content = msg.content,
                timestamp = msg.timestamp.toEpochMilli()
            )
        }
        return ResponseEntity.ok(SessionDetailResponse(sessionId = sessionId, messages = messages))
    }

    /** 대화 세션을 JSON 또는 Markdown으로 내보낸다. */
    @Operation(summary = "세션을 JSON 또는 Markdown으로 내보내기")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Exported session data"),
        ApiResponse(responseCode = "401", description = "Missing authenticated user context"),
        ApiResponse(responseCode = "403", description = "Access denied to session"),
        ApiResponse(responseCode = "404", description = "Session not found")
    ])
    @GetMapping("/sessions/{sessionId}/export")
    fun exportSession(
        @PathVariable sessionId: String,
        @RequestParam(defaultValue = "json") format: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        val userId = authenticatedUserId(exchange) ?: return unauthorizedResponse()
        if (!isSessionOwner(sessionId, userId, exchange)) {
            return sessionForbidden()
        }

        val memory = memoryStore.get(sessionId)
            ?: return notFoundResponse("Session not found: $sessionId")
        val messages = memory.getHistory()

        return when (format.lowercase()) {
            "markdown", "md" -> {
                val sb = StringBuilder()
                sb.appendLine("# Conversation: $sessionId\n")
                for (msg in messages) {
                    sb.appendLine("## ${msg.role.name.lowercase()}\n")
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                val safeId = sanitizeFilename(sessionId)
                ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$safeId.md\"")
                    .contentType(MediaType.TEXT_MARKDOWN)
                    .body(sb.toString())
            }
            else -> {
                val export = mapOf(
                    "sessionId" to sessionId,
                    "exportedAt" to System.currentTimeMillis(),
                    "messages" to messages.map { msg ->
                        mapOf(
                            "role" to msg.role.name.lowercase(),
                            "content" to msg.content,
                            "timestamp" to msg.timestamp.toEpochMilli()
                        )
                    }
                )
                val safeId = sanitizeFilename(sessionId)
                ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$safeId.json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(export)
            }
        }
    }

    /**
     * 세션과 모든 메시지를 삭제한다.
     * 세션 소유권을 검증한다. 진행 중인 요약 작업도 취소한다.
     */
    @Operation(summary = "세션 및 모든 메시지 삭제")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Session deleted"),
        ApiResponse(responseCode = "401", description = "Missing authenticated user context"),
        ApiResponse(responseCode = "403", description = "Access denied to session")
    ])
    @DeleteMapping("/sessions/{sessionId}")
    fun deleteSession(
        @PathVariable sessionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        val userId = authenticatedUserId(exchange) ?: return unauthorizedResponse()
        if (!isSessionOwner(sessionId, userId, exchange)) {
            return sessionForbidden()
        }

        logger.info { "audit category=session action=DELETE actor=$userId resourceId=$sessionId" }
        recordAdminAudit(
            store = adminAuditStore,
            category = "session",
            action = "DELETE",
            actor = userId,
            resourceType = "session",
            resourceId = sessionId
        )

        conversationManager?.cancelActiveSummarization(sessionId)
        memoryStore.remove(sessionId)
        conversationSummaryStore?.delete(sessionId)
        return ResponseEntity.noContent().build()
    }

    /**
     * 세션 소유자인지 확인한다.
     * WHY: deny-by-default -- owner가 기록되어 있고 userId와 일치해야만 true.
     * 관리자는 모든 세션에 접근 가능하다.
     */
    private fun isSessionOwner(
        sessionId: String,
        userId: String,
        exchange: ServerWebExchange
    ): Boolean {
        if (isAdmin(exchange)) return true
        val owner = memoryStore.getSessionOwner(sessionId)
        // deny-by-default: owner가 기록되어 있고 userId와 일치해야 한다
        return owner != null && owner == userId
    }

    private fun authenticatedUserId(exchange: ServerWebExchange): String? {
        return exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(UNSAFE_FILENAME_CHARS, "_").take(MAX_FILENAME_LENGTH)
    }

    companion object {
        private val UNSAFE_FILENAME_CHARS = Regex("[^a-zA-Z0-9._-]")
        private const val MAX_FILENAME_LENGTH = 100
    }

    private fun unauthorizedResponse(): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(error = "Authentication required", timestamp = Instant.now().toString()))
    }

    private fun sessionForbidden(): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse(error = "Access denied", timestamp = Instant.now().toString()))
    }

    /** 사용 가능한 LLM 프로바이더 목록을 조회한다. */
    @Operation(summary = "사용 가능한 LLM 프로바이더 목록 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of available LLM providers")
    ])
    @GetMapping("/models")
    fun listModels(): ModelsResponse {
        val defaultProvider = chatModelProvider.defaultProvider()
        val providers = chatModelProvider.availableProviders().map { name ->
            ModelInfo(name = name, isDefault = name == defaultProvider)
        }
        return ModelsResponse(models = providers, defaultModel = defaultProvider)
    }
}

// --- 응답 DTO ---

data class SessionResponse(
    val sessionId: String,
    val messageCount: Int,
    val lastActivity: Long,
    val preview: String
)

data class SessionDetailResponse(
    val sessionId: String,
    val messages: List<MessageResponse>
)

data class MessageResponse(
    val role: String,
    val content: String,
    val timestamp: Long
)

data class ModelsResponse(
    val models: List<ModelInfo>,
    val defaultModel: String
)

data class ModelInfo(
    val name: String,
    val isDefault: Boolean
)

// --- 매핑 확장 ---

private fun SessionSummary.toResponse() = SessionResponse(
    sessionId = sessionId,
    messageCount = messageCount,
    lastActivity = lastActivity.toEpochMilli(),
    preview = preview
)
