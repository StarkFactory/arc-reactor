package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import io.swagger.v3.oas.annotations.tags.Tag
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.memory.SessionSummary
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange

/**
 * Session Management and Model API Controller
 *
 * Provides REST APIs for managing conversation sessions
 * and querying available LLM providers.
 *
 * ## Endpoints
 * - GET /api/sessions          : List all sessions
 * - GET /api/sessions/{id}     : Get messages for a session
 * - DELETE /api/sessions/{id}  : Delete a session
 * - GET /api/models            : List available LLM providers
 */
@Tag(name = "Sessions", description = "Conversation session and LLM provider management")
@RestController
@RequestMapping("/api")
class SessionController(
    private val memoryStore: MemoryStore,
    private val chatModelProvider: ChatModelProvider
) {

    /**
     * List all sessions with summary metadata.
     * When auth is enabled, sessions are filtered by the authenticated userId.
     */
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
    @GetMapping("/sessions/{sessionId}")
    fun getSession(
        @PathVariable sessionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<SessionDetailResponse> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
        if (userId != null && !isSessionOwner(sessionId, userId)) {
            return ResponseEntity.status(403).build()
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
     * Delete a session and all its messages.
     * When auth is enabled, verifies session ownership.
     */
    @DeleteMapping("/sessions/{sessionId}")
    fun deleteSession(
        @PathVariable sessionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Void> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
        if (userId != null && !isSessionOwner(sessionId, userId)) {
            return ResponseEntity.status(403).build()
        }

        memoryStore.remove(sessionId)
        return ResponseEntity.noContent().build()
    }

    private fun isSessionOwner(sessionId: String, userId: String): Boolean {
        val owner = memoryStore.getSessionOwner(sessionId)
        // No owner recorded (legacy data or InMemory without userId) — allow access
        return owner == null || owner == userId
    }

    /**
     * List available LLM providers.
     */
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
