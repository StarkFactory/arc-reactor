package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.ResponseFormat
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.asFlux
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

/**
 * Chat API Controller
 *
 * Provides REST APIs for conversing with the AI agent.
 *
 * ## Endpoints
 * - POST /api/chat        : Standard response (full response at once)
 * - POST /api/chat/stream  : Streaming response (SSE, real-time token-by-token)
 */
@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val agentExecutor: AgentExecutor
) {

    /**
     * Standard chat - returns the full response at once
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/chat \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "3 + 5는 얼마야?"}'
     * ```
     */
    @PostMapping
    suspend fun chat(@Valid @RequestBody request: ChatRequest): ChatResponse {
        val result = agentExecutor.execute(
            AgentCommand(
                systemPrompt = request.systemPrompt ?: DEFAULT_SYSTEM_PROMPT,
                userPrompt = request.message,
                userId = request.userId,
                metadata = request.metadata ?: emptyMap(),
                responseFormat = request.responseFormat ?: ResponseFormat.TEXT,
                responseSchema = request.responseSchema
            )
        )
        return ChatResponse(
            content = result.content,
            success = result.success,
            toolsUsed = result.toolsUsed,
            errorMessage = result.errorMessage
        )
    }

    /**
     * Streaming chat - real-time response via SSE
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/chat/stream \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "지금 몇 시야?"}' \
     *   --no-buffer
     * ```
     */
    @PostMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chatStream(@Valid @RequestBody request: ChatRequest): Flux<String> {
        val flow: Flow<String> = agentExecutor.executeStream(
            AgentCommand(
                systemPrompt = request.systemPrompt ?: DEFAULT_SYSTEM_PROMPT,
                userPrompt = request.message,
                userId = request.userId,
                metadata = request.metadata ?: emptyMap(),
                responseFormat = request.responseFormat ?: ResponseFormat.TEXT,
                responseSchema = request.responseSchema
            )
        )
        return flow.asFlux()
    }

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful AI assistant. You can use tools when needed. " +
                "Answer in the same language as the user's message."
    }
}

/**
 * Chat request
 */
data class ChatRequest(
    @field:NotBlank(message = "message must not be blank")
    val message: String,
    val systemPrompt: String? = null,
    val userId: String? = null,
    val metadata: Map<String, Any>? = null,
    val responseFormat: ResponseFormat? = null,
    val responseSchema: String? = null
)

/**
 * Chat response
 */
data class ChatResponse(
    val content: String?,
    val success: Boolean,
    val toolsUsed: List<String> = emptyList(),
    val errorMessage: String? = null
)
