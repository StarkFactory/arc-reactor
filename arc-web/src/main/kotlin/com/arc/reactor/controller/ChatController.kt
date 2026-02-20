package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.MediaAttachment
import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.persona.PersonaStore
import mu.KotlinLogging
import com.arc.reactor.prompt.PromptTemplateStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.reactor.asFlux
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.util.MimeType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Flux
import java.net.URI
import java.net.URISyntaxException

private val logger = KotlinLogging.logger {}

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
 * - `error` : Error occurred (data = error message)
 * - `done` : Stream complete
 */
@Tag(name = "Chat", description = "AI agent chat endpoints")
@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val agentExecutor: AgentExecutor,
    private val personaStore: PersonaStore? = null,
    private val promptTemplateStore: PromptTemplateStore? = null,
    private val properties: AgentProperties = AgentProperties(),
    private val intentResolver: IntentResolver? = null,
    private val memoryStore: MemoryStore? = null
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
    @Operation(summary = "Send a message and receive a complete response")
    @PostMapping
    suspend fun chat(
        @Valid @RequestBody request: ChatRequest,
        exchange: ServerWebExchange
    ): ChatResponse {
        var command = AgentCommand(
            systemPrompt = resolveSystemPrompt(request),
            userPrompt = request.message,
            model = request.model,
            userId = resolveUserId(exchange, request),
            metadata = resolveMetadata(request),
            responseFormat = request.responseFormat ?: ResponseFormat.TEXT,
            responseSchema = request.responseSchema,
            media = resolveMediaUrls(request.mediaUrls)
        )

        command = applyIntentProfile(command, request)

        val result = agentExecutor.execute(command)
        return ChatResponse(
            content = result.content,
            success = result.success,
            model = command.model,
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
    @Operation(
        summary = "Streaming chat (SSE)",
        description = "Real-time streaming response via Server-Sent Events.\n\n" +
            "SSE event types:\n" +
            "- `message` : LLM text token chunk\n" +
            "- `tool_start` : Tool execution started (data = tool name)\n" +
            "- `tool_end` : Tool execution completed (data = tool name)\n" +
            "- `error` : Error occurred (data = error message)\n" +
            "- `done` : Stream complete",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "SSE stream",
                content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE)]
            )
        ]
    )
    @PostMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun chatStream(
        @Valid @RequestBody request: ChatRequest,
        exchange: ServerWebExchange
    ): Flux<ServerSentEvent<String>> {
        var command = AgentCommand(
            systemPrompt = resolveSystemPrompt(request),
            userPrompt = request.message,
            model = request.model,
            userId = resolveUserId(exchange, request),
            metadata = resolveMetadata(request),
            responseFormat = request.responseFormat ?: ResponseFormat.TEXT,
            responseSchema = request.responseSchema,
            media = resolveMediaUrls(request.mediaUrls)
        )

        command = applyIntentProfile(command, request)

        val flow: Flow<String> = agentExecutor.executeStream(command)

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

        val userId = resolveUserId(exchange, request)
        return eventFlow.asFlux()
            .doOnCancel { logger.debug { "SSE stream cancelled by client (userId=$userId)" } }
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
     * Apply intent profile to the command if intent classification is enabled.
     * Returns the original command if intent is disabled or no confident match.
     */
    private suspend fun applyIntentProfile(command: AgentCommand, request: ChatRequest): AgentCommand {
        if (intentResolver == null) return command

        val context = buildClassificationContext(command, request)
        val resolved = intentResolver.resolve(command.userPrompt, context) ?: return command
        return intentResolver.applyProfile(command, resolved)
    }

    /**
     * Build classification context from request and conversation history.
     */
    private fun buildClassificationContext(command: AgentCommand, request: ChatRequest): ClassificationContext {
        val sessionId = request.metadata?.get("sessionId") as? String
        val history = if (sessionId != null && memoryStore != null) {
            memoryStore.get(sessionId)
                ?.getHistory()
                ?.takeLast(4)
                ?: emptyList()
        } else {
            emptyList()
        }

        return ClassificationContext(
            userId = command.userId,
            conversationHistory = history,
            metadata = request.metadata ?: emptyMap()
        )
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
     * 2. promptTemplateId → active version from PromptTemplateStore
     * 3. request.systemPrompt → direct override
     * 4. Default persona from PersonaStore
     * 5. Hardcoded fallback
     */
    private fun resolveSystemPrompt(request: ChatRequest): String {
        if (request.personaId != null && personaStore != null) {
            personaStore.get(request.personaId)?.systemPrompt?.let { return it }
        }
        if (request.promptTemplateId != null && promptTemplateStore != null) {
            promptTemplateStore.getActiveVersion(request.promptTemplateId)?.content?.let { return it }
        }
        if (!request.systemPrompt.isNullOrBlank()) return request.systemPrompt
        personaStore?.getDefault()?.systemPrompt?.let { return it }
        return DEFAULT_SYSTEM_PROMPT
    }

    /**
     * Build metadata enriched with prompt version info when a template is used.
     */
    private fun resolveMetadata(request: ChatRequest): Map<String, Any> {
        val base = request.metadata ?: emptyMap()
        val withChannel = if (base.containsKey("channel")) base else (base + mapOf("channel" to "web"))
        if (request.promptTemplateId == null || promptTemplateStore == null) return withChannel

        val activeVersion = promptTemplateStore.getActiveVersion(request.promptTemplateId) ?: return withChannel
        return withChannel + mapOf(
            "promptTemplateId" to request.promptTemplateId,
            "promptVersionId" to activeVersion.id,
            "promptVersion" to activeVersion.version
        )
    }

    /**
     * Convert [MediaUrlRequest] list to [MediaAttachment] list for URI-based media.
     * Returns empty list when multimodal is disabled.
     */
    private fun resolveMediaUrls(mediaUrls: List<MediaUrlRequest>?): List<MediaAttachment> {
        if (!properties.multimodal.enabled) return emptyList()
        if (mediaUrls.isNullOrEmpty()) return emptyList()
        return mediaUrls.map { req ->
            MediaAttachment(
                mimeType = parseMimeType(req.mimeType),
                uri = parseMediaUri(req.url)
            )
        }
    }

    private fun parseMimeType(raw: String): MimeType {
        val normalized = raw.trim()
        return try {
            MimeType.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            throw ServerWebInputException("Invalid media mimeType: $raw")
        }
    }

    private fun parseMediaUri(raw: String): URI {
        val normalized = raw.trim()
        val uri = try {
            URI(normalized)
        } catch (_: URISyntaxException) {
            throw ServerWebInputException("Invalid media URL: $raw")
        }
        if (!uri.isAbsolute) {
            throw ServerWebInputException("Invalid media URL: $raw")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw ServerWebInputException("Invalid media URL: $raw")
        }
        if (uri.host.isNullOrBlank()) {
            throw ServerWebInputException("Invalid media URL: $raw")
        }
        return uri
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
    @field:Size(max = 50000, message = "message must not exceed 50000 characters")
    val message: String,
    val model: String? = null,
    @field:Size(max = 10000, message = "systemPrompt must not exceed 10000 characters")
    val systemPrompt: String? = null,
    val personaId: String? = null,
    val promptTemplateId: String? = null,
    val userId: String? = null,
    @field:Size(max = 20, message = "metadata must not exceed 20 entries")
    val metadata: Map<String, Any>? = null,
    val responseFormat: ResponseFormat? = null,
    @field:Size(max = 10000, message = "responseSchema must not exceed 10000 characters")
    val responseSchema: String? = null,
    val mediaUrls: List<MediaUrlRequest>? = null
)

/**
 * Media URL reference for JSON-based multimodal requests.
 *
 * ```json
 * {
 *   "message": "Describe this image",
 *   "mediaUrls": [
 *     { "url": "https://example.com/photo.png", "mimeType": "image/png" }
 *   ]
 * }
 * ```
 */
data class MediaUrlRequest(
    val url: String,
    val mimeType: String
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
