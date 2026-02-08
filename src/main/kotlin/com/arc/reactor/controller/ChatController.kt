package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.persona.PersonaStore
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.reactor.asFlux
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux

/**
 * Chat API Controller
 *
 * Provides REST APIs for conversing with the AI agent.
 *
 * ## Endpoints
 * - POST /api/chat        : Standard response (full response at once)
 * - POST /api/chat/stream  : Streaming response (SSE, real-time token-by-token)
 *
 * ## SSE Event Types (streaming)
 * - `message` : Text token chunk
 * - `tool_start` : Tool execution started (data = tool name)
 * - `tool_end` : Tool execution completed (data = tool name)
 * - `done` : Stream complete
 */
@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val agentExecutor: AgentExecutor,
    private val personaStore: PersonaStore? = null
) {

    /**
     * Standard chat - returns the full response at once
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/chat \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "What is 3 + 5?"}'
     * ```
     */
    @PostMapping
    suspend fun chat(
        @Valid @RequestBody request: ChatRequest,
        exchange: ServerWebExchange
    ): ChatResponse {
        val result = agentExecutor.execute(
            AgentCommand(
                systemPrompt = resolveSystemPrompt(request),
                userPrompt = request.message,
                model = request.model,
                userId = resolveUserId(exchange, request),
                metadata = request.metadata ?: emptyMap(),
                responseFormat = request.responseFormat ?: ResponseFormat.TEXT,
                responseSchema = request.responseSchema
            )
        )
        return ChatResponse(
            content = result.content,
            success = result.success,
            model = request.model,
            toolsUsed = result.toolsUsed,
            errorMessage = result.errorMessage
        )
    }

    /**
     * Streaming chat - real-time response via typed SSE events
     *
     * SSE event types:
     * - `event: message` + `data: <text>` - LLM text token
     * - `event: tool_start` + `data: <tool_name>` - Tool execution started
     * - `event: tool_end` + `data: <tool_name>` - Tool execution completed
     * - `event: done` + `data:` - Stream complete
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/chat/stream \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "What time is it now?"}' \
     *   --no-buffer
     * ```
     */
    @PostMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chatStream(
        @Valid @RequestBody request: ChatRequest,
        exchange: ServerWebExchange
    ): Flux<ServerSentEvent<String>> {
        val flow: Flow<String> = agentExecutor.executeStream(
            AgentCommand(
                systemPrompt = resolveSystemPrompt(request),
                userPrompt = request.message,
                model = request.model,
                userId = resolveUserId(exchange, request),
                metadata = request.metadata ?: emptyMap(),
                responseFormat = request.responseFormat ?: ResponseFormat.TEXT,
                responseSchema = request.responseSchema
            )
        )

        val eventFlow: Flow<ServerSentEvent<String>> = flow
            .map { chunk -> toServerSentEvent(chunk) }
            .onCompletion {
                emit(
                    ServerSentEvent.builder<String>()
                        .event("done")
                        .data("")
                        .build()
                )
            }

        return eventFlow.asFlux()
    }

    private fun toServerSentEvent(chunk: String): ServerSentEvent<String> {
        val parsed = StreamEventMarker.parse(chunk)
        return if (parsed != null) {
            ServerSentEvent.builder<String>()
                .event(parsed.first)
                .data(parsed.second)
                .build()
        } else {
            ServerSentEvent.builder<String>()
                .event("message")
                .data(chunk)
                .build()
        }
    }

    /**
     * Resolve userId with priority:
     * 1. JWT token (from WebFilter via exchange attributes)
     * 2. Request body userId field
     * 3. "anonymous" fallback
     */
    private fun resolveUserId(exchange: ServerWebExchange, request: ChatRequest): String {
        return exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
            ?: request.userId
            ?: "anonymous"
    }

    /**
     * Resolve the system prompt with priority:
     * 1. personaId → lookup from PersonaStore
     * 2. request.systemPrompt → direct override
     * 3. Default persona from PersonaStore
     * 4. Hardcoded fallback
     */
    private fun resolveSystemPrompt(request: ChatRequest): String {
        if (request.personaId != null && personaStore != null) {
            personaStore.get(request.personaId)?.systemPrompt?.let { return it }
        }
        if (!request.systemPrompt.isNullOrBlank()) return request.systemPrompt
        personaStore?.getDefault()?.systemPrompt?.let { return it }
        return DEFAULT_SYSTEM_PROMPT
    }

    companion object {
        internal const val DEFAULT_SYSTEM_PROMPT =
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
    val model: String? = null,
    val systemPrompt: String? = null,
    val personaId: String? = null,
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
    val model: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val errorMessage: String? = null
)
