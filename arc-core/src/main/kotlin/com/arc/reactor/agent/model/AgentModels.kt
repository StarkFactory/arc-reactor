package com.arc.reactor.agent.model

import java.time.Instant
import org.springframework.util.MimeType

/**
 * Response format for structured output
 */
enum class ResponseFormat {
    /** Plain text response (default) */
    TEXT,

    /** JSON response (system prompt instructs LLM to output valid JSON) */
    JSON,

    /** YAML response (system prompt instructs LLM to output valid YAML) */
    YAML
}

/**
 * Agent execution mode
 */
enum class AgentMode {
    /** Standard mode (single response) */
    STANDARD,

    /** ReAct mode (Thought-Action-Observation loop) */
    REACT,

    /** Streaming mode — executeStream() works regardless of AgentMode. Reserved for future mode branching */
    STREAMING
}

/**
 * Media attachment for multimodal input (images, audio, video, etc.)
 *
 * Wraps a media resource that can be sent to multimodal LLMs alongside text prompts.
 * Supports both URI-based references and raw byte data.
 *
 * ## Example
 * ```kotlin
 * // From URL
 * val imageUrl = MediaAttachment(
 *     mimeType = MimeTypeUtils.IMAGE_PNG,
 *     uri = URI("https://example.com/photo.png")
 * )
 *
 * // From raw bytes
 * val imageBytes = MediaAttachment(
 *     mimeType = MimeTypeUtils.IMAGE_JPEG,
 *     data = fileBytes,
 *     name = "photo.jpg"
 * )
 * ```
 */
data class MediaAttachment(
    val mimeType: MimeType,
    val data: ByteArray? = null,
    val uri: java.net.URI? = null,
    val name: String? = null
) {
    init {
        require(data != null || uri != null) { "Either data or uri must be provided" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaAttachment) return false
        return mimeType == other.mimeType &&
            data?.contentEquals(other.data ?: byteArrayOf()) ?: (other.data == null) &&
            uri == other.uri && name == other.name
    }

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + (uri?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}

/**
 * Agent execution command
 */
data class AgentCommand(
    val systemPrompt: String,
    val userPrompt: String,
    val mode: AgentMode = AgentMode.REACT,
    val model: String? = null,
    val conversationHistory: List<Message> = emptyList(),
    val temperature: Double? = null,
    val maxToolCalls: Int = 10,
    val userId: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,
    val responseSchema: String? = null,
    val media: List<MediaAttachment> = emptyList()
)

/**
 * Message (for conversation history)
 */
data class Message(
    val role: MessageRole,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val media: List<MediaAttachment> = emptyList()
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
    val errorCode: AgentErrorCode? = null,
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
            errorCode: AgentErrorCode? = null,
            durationMs: Long = 0
        ) = AgentResult(
            success = false,
            content = null,
            errorCode = errorCode,
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
