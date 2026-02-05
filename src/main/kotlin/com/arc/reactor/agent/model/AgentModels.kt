package com.arc.reactor.agent.model

import java.time.Instant

/**
 * Agent 실행 모드
 */
enum class AgentMode {
    /** 기본 모드 (단일 응답) */
    STANDARD,

    /** ReAct 모드 (Thought-Action-Observation 루프) */
    REACT,

    /** 스트리밍 모드 */
    STREAMING
}

/**
 * Agent 실행 명령
 */
data class AgentCommand(
    val systemPrompt: String,
    val userPrompt: String,
    val mode: AgentMode = AgentMode.REACT,
    val conversationHistory: List<Message> = emptyList(),
    val temperature: Double? = null,
    val maxToolCalls: Int = 10,
    val userId: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * 메시지 (대화 기록용)
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
 * Agent 실행 결과
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
 * 토큰 사용량
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}
