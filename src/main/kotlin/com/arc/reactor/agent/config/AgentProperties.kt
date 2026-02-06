package com.arc.reactor.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Arc Reactor Agent Configuration
 */
@ConfigurationProperties(prefix = "arc.reactor")
data class AgentProperties(
    /** LLM configuration */
    val llm: LlmProperties = LlmProperties(),

    /** Guard configuration */
    val guard: GuardProperties = GuardProperties(),

    /** RAG configuration */
    val rag: RagProperties = RagProperties(),

    /** Concurrency configuration */
    val concurrency: ConcurrencyProperties = ConcurrencyProperties(),

    /** Maximum tools per request */
    val maxToolsPerRequest: Int = 20,

    /** Maximum tool calls (prevents infinite loops) */
    val maxToolCalls: Int = 10
)

data class LlmProperties(
    /** Default temperature */
    val temperature: Double = 0.3,

    /** Maximum output tokens */
    val maxOutputTokens: Int = 4096,

    /** Timeout (ms) */
    val timeoutMs: Long = 60000,

    /** Maximum conversation history turns */
    val maxConversationTurns: Int = 10
)

data class GuardProperties(
    /** Guard enabled */
    val enabled: Boolean = true,

    /** Requests per minute limit */
    val rateLimitPerMinute: Int = 10,

    /** Requests per hour limit */
    val rateLimitPerHour: Int = 100,

    /** Maximum input length */
    val maxInputLength: Int = 10000,

    /** Injection detection enabled */
    val injectionDetectionEnabled: Boolean = true
)

data class ConcurrencyProperties(
    /** Maximum concurrent requests */
    val maxConcurrentRequests: Int = 20,

    /** Request timeout (milliseconds) */
    val requestTimeoutMs: Long = 30000
)

data class RagProperties(
    /** RAG enabled */
    val enabled: Boolean = false,

    /** Search similarity threshold */
    val similarityThreshold: Double = 0.7,

    /** Number of search results */
    val topK: Int = 10,

    /** Enable re-ranking */
    val rerankEnabled: Boolean = true,

    /** Maximum context tokens */
    val maxContextTokens: Int = 4000
)
