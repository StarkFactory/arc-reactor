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
 * 채팅 API 컨트롤러
 *
 * AI 에이전트와 대화하기 위한 REST API를 제공합니다.
 *
 * ## 엔드포인트
 * - POST /api/chat        : 일반 응답 (한 번에 전체 응답)
 * - POST /api/chat/stream  : 스트리밍 응답 (SSE, 실시간 토큰 단위)
 */
@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val agentExecutor: AgentExecutor
) {

    /**
     * 일반 채팅 - 전체 응답을 한 번에 반환
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
     * 스트리밍 채팅 - SSE로 실시간 응답
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
            "You are a helpful AI assistant. You can use tools when needed. Answer in the same language as the user's message."
    }
}

/**
 * 채팅 요청
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
 * 채팅 응답
 */
data class ChatResponse(
    val content: String?,
    val success: Boolean,
    val toolsUsed: List<String> = emptyList(),
    val errorMessage: String? = null
)
