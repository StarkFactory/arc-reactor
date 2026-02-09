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

    /** Retry configuration */
    val retry: RetryProperties = RetryProperties(),

    /** Maximum tools per request */
    val maxToolsPerRequest: Int = 20,

    /** Maximum tool calls (prevents infinite loops) */
    val maxToolCalls: Int = 10,

    /** CORS configuration */
    val cors: CorsProperties = CorsProperties(),

    /** Security headers configuration */
    val securityHeaders: SecurityHeadersProperties = SecurityHeadersProperties(),

    /** MCP security configuration */
    val mcpSecurity: McpSecurityProperties = McpSecurityProperties()
)

data class LlmProperties(
    /** Default LLM provider (e.g., "gemini", "openai", "anthropic") */
    val defaultProvider: String = "gemini",

    /** Default temperature */
    val temperature: Double = 0.3,

    /** Maximum output tokens */
    val maxOutputTokens: Int = 4096,

    /** Maximum conversation history turns */
    val maxConversationTurns: Int = 10,

    /** Maximum context window tokens (for token-based message trimming) */
    val maxContextWindowTokens: Int = 128000
)

data class RetryProperties(
    /** Maximum number of retry attempts */
    val maxAttempts: Int = 3,

    /** Initial delay between retries (milliseconds) */
    val initialDelayMs: Long = 1000,

    /** Backoff multiplier */
    val multiplier: Double = 2.0,

    /** Maximum delay between retries (milliseconds) */
    val maxDelayMs: Long = 10000
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

data class CorsProperties(
    /** CORS enabled (opt-in) */
    val enabled: Boolean = false,

    /** Allowed origins */
    val allowedOrigins: List<String> = listOf("http://localhost:3000"),

    /** Allowed HTTP methods */
    val allowedMethods: List<String> = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS"),

    /** Allowed headers */
    val allowedHeaders: List<String> = listOf("*"),

    /** Allow credentials (cookies, Authorization header) */
    val allowCredentials: Boolean = true,

    /** Preflight cache duration in seconds */
    val maxAge: Long = 3600
)

data class SecurityHeadersProperties(
    /** Security headers enabled (default: true) */
    val enabled: Boolean = true
)

data class McpSecurityProperties(
    /** Allowed MCP server names (empty = allow all) */
    val allowedServerNames: Set<String> = emptySet(),

    /** Maximum tool output length in characters */
    val maxToolOutputLength: Int = 50_000
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
