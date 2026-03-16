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
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Session Management and Model API Controller
 *
 * Provides REST APIs for managing conversation sessions
 * and querying available LLM providers.
 *
 * ## Endpoints
 * - GET /api/sessions                          : List all sessions
 * - GET /api/sessions/{id}                     : Get messages for a session
 * - GET /api/sessions/{id}/export?format=json  : Export as JSON
 * - GET /api/sessions/{id}/export?format=markdown : Export as Markdown
 * - DELETE /api/sessions/{id}                  : Delete a session
 * - GET /api/models                            : List available LLM providers
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
     * List all sessions with summary metadata.
     * Sessions are filtered by the authenticated userId.
     */
    @Operation(summary = "List all conversation sessions")
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
     * Get all messages for a specific session.
     * Verifies session ownership.
     */
    @Operation(summary = "Get messages for a specific session")
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
            ?: return ResponseEntity.notFound().build()
        val messages = memory.getHistory().map { msg ->
            MessageResponse(
                role = msg.role.name.lowercase(),
                content = msg.content,
                timestamp = msg.timestamp.toEpochMilli()
            )
        }
        return ResponseEntity.ok(SessionDetailResponse(sessionId = sessionId, messages = messages))
    }

    /**
     * Export a conversation session as JSON or Markdown.
     */
    @Operation(summary = "Export a session as JSON or Markdown")
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
            ?: return ResponseEntity.notFound().build()
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
     * Delete a session and all its messages.
     * Verifies session ownership.
     */
    @Operation(summary = "Delete a session and all its messages")
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

    private fun isSessionOwner(
        sessionId: String,
        userId: String,
        exchange: ServerWebExchange
    ): Boolean {
        if (isAdmin(exchange)) return true
        val owner = memoryStore.getSessionOwner(sessionId)
        // Deny-by-default: owner must be recorded and must match userId
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

    /**
     * List available LLM providers.
     */
    @Operation(summary = "List available LLM providers")
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

// --- Response DTOs ---

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

// --- Mapping extensions ---

private fun SessionSummary.toResponse() = SessionResponse(
    sessionId = sessionId,
    messageCount = messageCount,
    lastActivity = lastActivity.toEpochMilli(),
    preview = preview
)
