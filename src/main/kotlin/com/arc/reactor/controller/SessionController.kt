package com.arc.reactor.controller

import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.memory.SessionSummary
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

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
@RestController
@RequestMapping("/api")
class SessionController(
    private val memoryStore: MemoryStore,
    private val chatModelProvider: ChatModelProvider
) {

    /**
     * List all sessions with summary metadata.
     */
    @GetMapping("/sessions")
    fun listSessions(): List<SessionResponse> {
        return memoryStore.listSessions().map { it.toResponse() }
    }

    /**
     * Get all messages for a specific session.
     */
    @GetMapping("/sessions/{sessionId}")
    fun getSession(@PathVariable sessionId: String): ResponseEntity<SessionDetailResponse> {
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
     */
    @DeleteMapping("/sessions/{sessionId}")
    fun deleteSession(@PathVariable sessionId: String): ResponseEntity<Void> {
        memoryStore.remove(sessionId)
        return ResponseEntity.noContent().build()
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
