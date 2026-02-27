package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import io.swagger.v3.oas.annotations.Operation
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
import java.time.Instant

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
    summaryStoreProvider: ObjectProvider<ConversationSummaryStore>,
    conversationManagerProvider: ObjectProvider<ConversationManager>
) {

    private val conversationSummaryStore: ConversationSummaryStore? = summaryStoreProvider.ifAvailable
    private val conversationManager: ConversationManager? = conversationManagerProvider.ifAvailable

    /**
     * List all sessions with summary metadata.
     * When auth is enabled, sessions are filtered by the authenticated userId.
     */
    @Operation(summary = "List all conversation sessions")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of sessions")
    ])
    @GetMapping("/sessions")
    fun listSessions(exchange: ServerWebExchange): List<SessionResponse> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
        val sessions = if (userId != null) {
            memoryStore.listSessionsByUserId(userId)
        } else {
            memoryStore.listSessions()
        }
        return sessions.map { it.toResponse() }
    }

    /**
     * Get all messages for a specific session.
     * When auth is enabled, verifies session ownership.
     */
    @Operation(summary = "Get messages for a specific session")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Session messages"),
        ApiResponse(responseCode = "403", description = "Access denied to session"),
        ApiResponse(responseCode = "404", description = "Session not found")
    ])
    @GetMapping("/sessions/{sessionId}")
    fun getSession(
        @PathVariable sessionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
        if (userId != null && !isSessionOwner(sessionId, userId)) {
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
        ApiResponse(responseCode = "403", description = "Access denied to session"),
        ApiResponse(responseCode = "404", description = "Session not found")
    ])
    @GetMapping("/sessions/{sessionId}/export")
    fun exportSession(
        @PathVariable sessionId: String,
        @RequestParam(defaultValue = "json") format: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
        if (userId != null && !isSessionOwner(sessionId, userId)) {
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
                ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$sessionId.md\"")
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
                ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$sessionId.json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(export)
            }
        }
    }

    /**
     * Delete a session and all its messages.
     * When auth is enabled, verifies session ownership.
     */
    @Operation(summary = "Delete a session and all its messages")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Session deleted"),
        ApiResponse(responseCode = "403", description = "Access denied to session")
    ])
    @DeleteMapping("/sessions/{sessionId}")
    fun deleteSession(
        @PathVariable sessionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
        if (userId != null && !isSessionOwner(sessionId, userId)) {
            return sessionForbidden()
        }

        conversationManager?.cancelActiveSummarization(sessionId)
        memoryStore.remove(sessionId)
        conversationSummaryStore?.delete(sessionId)
        return ResponseEntity.noContent().build()
    }

    private fun isSessionOwner(sessionId: String, userId: String): Boolean {
        val owner = memoryStore.getSessionOwner(sessionId)
        // No owner recorded (legacy data or InMemory without userId) — allow access
        return owner == null || owner == userId
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
