package com.arc.reactor.agent.model

import java.time.Instant

/**
 * Response format for structured output
 */
enum class ResponseFormat {
    /** Plain text response (default) */
    TEXT,

    /** JSON response (system prompt instructs LLM to output valid JSON) */
    JSON
}

/**
 * Agent execution mode
 */
enum class AgentMode {
    /** Standard mode (single response) */
    STANDARD,

    /** ReAct mode (Thought-Action-Observation loop) */
    REACT,

    /** Streaming mode (planned for future implementation) */
    STREAMING
}

/**
 * Agent execution command
 */
data class AgentCommand(
    val systemPrompt: String,
    val userPrompt: String,
    val mode: AgentMode = AgentMode.REACT,
    val conversationHistory: List<Message> = emptyList(),
    val temperature: Double? = null,
    val maxToolCalls: Int = 10,
    val userId: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,
    val responseSchema: String? = null
)

/**
 * Message (for conversation history)
 */
data class Message(
    val role: MessageRole,
    val content: String,
    val timestamp: Instant = Instant.now()
)

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

/**
 * Agent execution result
 */
data class AgentResult(
    val success: Boolean,
    val content: String?,
    val errorMessage: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val tokenUsage: TokenUsage? = null,
    val durationMs: Long = 0,
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        fun success(
            content: String,
            toolsUsed: List<String> = emptyList(),
            tokenUsage: TokenUsage? = null,
            durationMs: Long = 0
        ) = AgentResult(
            success = true,
            content = content,
            toolsUsed = toolsUsed,
            tokenUsage = tokenUsage,
            durationMs = durationMs
        )

        fun failure(
            errorMessage: String,
            durationMs: Long = 0
        ) = AgentResult(
            success = false,
            content = null,
            errorMessage = errorMessage,
            durationMs = durationMs
        )
    }
}

/**
 * Token usage
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int = promptTokens + completionTokens
)
