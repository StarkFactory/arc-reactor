package com.arc.reactor.agent.config

/**
 * Conversation memory configuration.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     memory:
 *       summary:
 *         enabled: true
 *         trigger-message-count: 20
 *         recent-message-count: 10
 *         llm-model: gemini-2.0-flash
 *         max-narrative-tokens: 500
 * ```
 */
data class MemoryProperties(
    /** Hierarchical summary configuration */
    val summary: SummaryProperties = SummaryProperties(),

    /** Per-user long-term memory configuration */
    val user: UserMemoryProperties = UserMemoryProperties()
)

/**
 * Per-user long-term memory configuration.
 *
 * When enabled, the agent remembers user-specific facts, preferences, and recent topics
 * across conversation sessions. Memory is injected into the system prompt automatically.
 */
data class UserMemoryProperties(
    /** Enable per-user long-term memory. Disabled by default (opt-in). */
    val enabled: Boolean = false,

    /** Maximum number of recent topics to retain per user. */
    val maxRecentTopics: Int = 10,

    /** JDBC-specific settings */
    val jdbc: UserMemoryJdbcProperties = UserMemoryJdbcProperties()
)

/** JDBC-specific settings for user memory persistence. */
data class UserMemoryJdbcProperties(
    /** Database table name for user memory records. */
    val tableName: String = "user_memories"
)

/**
 * Hierarchical conversation summary configuration.
 *
 * When enabled, old messages are summarized into structured facts + narrative
 * while recent messages are preserved verbatim. This prevents context loss
 * during long conversations.
 */
data class SummaryProperties(
    /** Enable hierarchical memory summarization. Disabled by default (opt-in). */
    val enabled: Boolean = false,

    /** Minimum message count before summarization triggers. */
    val triggerMessageCount: Int = 20,

    /** Number of recent messages to keep verbatim (not summarized). */
    val recentMessageCount: Int = 10,

    /** LLM provider for summarization (null = use default provider). */
    val llmModel: String? = null,

    /** Maximum token budget for the narrative summary. */
    val maxNarrativeTokens: Int = 500
)

/**
 * Tracing configuration for Arc Reactor.
 *
 * When enabled, agent request spans, guard spans, LLM call spans, and tool call spans
 * are emitted. If OpenTelemetry is not on the classpath, a no-op tracer is used and
 * all operations are zero-cost.
 */
data class TracingProperties(
    /** Enable span emission. Default ON — the no-op tracer has zero overhead when OTel is absent. */
    val enabled: Boolean = true,

    /** Service name attached to spans as `service.name`. */
    val serviceName: String = "arc-reactor",

    /**
     * Include user ID as a span attribute.
     * Disabled by default to prevent accidental PII leakage in traces.
     */
    val includeUserId: Boolean = false
)
