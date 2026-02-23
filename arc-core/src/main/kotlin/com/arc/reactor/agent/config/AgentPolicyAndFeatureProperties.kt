package com.arc.reactor.agent.config

import com.arc.reactor.guard.output.impl.OutputBlockPattern

/**
 * Human-in-the-Loop approval configuration.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     approval:
 *       enabled: true
 *       timeout-ms: 300000
 *       tool-names:
 *         - delete_order
 *         - process_refund
 * ```
 */
data class ApprovalProperties(
    /** Enable Human-in-the-Loop approval */
    val enabled: Boolean = false,

    /** Default approval timeout in milliseconds (0 = 5 minutes) */
    val timeoutMs: Long = 300_000,

    /** Tool names that require approval (empty = use custom ToolApprovalPolicy) */
    val toolNames: Set<String> = emptySet()
)

/**
 * Tool policy configuration.
 *
 * Primarily used to safely handle "write" (side-effecting) tools in enterprise environments.
 * Typical strategy:
 * - Web: require HITL approval for write tools
 * - Slack: deny write tools (chat-first UX + risk)
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     tool-policy:
 *       enabled: true
 *       write-tool-names:
 *         - jira_create_issue
 *         - confluence_update_page
 *       deny-write-channels:
 *         - slack
 * ```
 */
data class ToolPolicyProperties(
    /** Enable tool policy enforcement (opt-in). */
    val enabled: Boolean = false,

    /**
     * Dynamic tool policy configuration (admin-managed).
     *
     * When enabled, Arc Reactor can load and update tool policy values at runtime via DB/API,
     * allowing enterprises to change write-tool rules without redeploying.
     */
    val dynamic: ToolPolicyDynamicProperties = ToolPolicyDynamicProperties(),

    /** Tool names considered "write" (side-effecting). */
    val writeToolNames: Set<String> = emptySet(),

    /** Channels where write tools are denied (fail-closed). */
    val denyWriteChannels: Set<String> = setOf("slack"),

    /**
     * Exception list: write tool names that are allowed even in [denyWriteChannels].
     *
     * This is useful when you want a strict default (deny on chat channels), but still
     * allow a small subset of safe write operations (e.g., create a Jira issue) in Slack.
     */
    val allowWriteToolNamesInDenyChannels: Set<String> = emptySet(),

    /**
     * Channel-scoped allowlist for deny channels.
     *
     * If a channel is in [denyWriteChannels], write tools are blocked by default. This map can then
     * allow specific write tools for specific channels.
     *
     * Example:
     * ```yaml
     * arc:
     *   reactor:
     *     tool-policy:
     *       enabled: true
     *       deny-write-channels: [slack, discord]
     *       allow-write-tool-names-by-channel:
     *         slack: [jira_create_issue]
     * ```
     */
    val allowWriteToolNamesByChannel: Map<String, Set<String>> = emptyMap(),

    /** Error message returned when a tool call is denied by policy. */
    val denyWriteMessage: String = "Error: This tool is not allowed in this channel"
)

data class ToolPolicyDynamicProperties(
    /** Enable DB-backed tool policy (admin API updates + periodic refresh). */
    val enabled: Boolean = false,

    /** Cache refresh interval in milliseconds when dynamic policy is enabled. */
    val refreshMs: Long = 10_000
)

/**
 * Multimodal (file upload / media URL) configuration.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     multimodal:
 *       enabled: false   # disable file uploads and media URL processing
 * ```
 */
data class MultimodalProperties(
    /** Enable multimodal support (file uploads via /api/chat/multipart and mediaUrls in JSON requests) */
    val enabled: Boolean = true
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

    /** Query transformer mode: passthrough|hyde */
    val queryTransformer: String = "passthrough",

    /** RAG ingestion policy (Q&A -> candidate queue -> reviewed vector ingestion) */
    val ingestion: RagIngestionProperties = RagIngestionProperties(),

    /** Maximum context tokens */
    val maxContextTokens: Int = 4000
)

data class RagIngestionProperties(
    /** Master switch for ingestion candidate capture. */
    val enabled: Boolean = false,

    /** Runtime DB-backed policy management switch. */
    val dynamic: RagIngestionDynamicProperties = RagIngestionDynamicProperties(),

    /** Whether admin review is required before vector ingestion. */
    val requireReview: Boolean = true,

    /** Allowed channels for auto-capture. Empty = capture from all channels. */
    val allowedChannels: Set<String> = emptySet(),

    /** Minimum query length to be considered knowledge-worthy. */
    val minQueryChars: Int = 10,

    /** Minimum response length to be considered knowledge-worthy. */
    val minResponseChars: Int = 20,

    /** Regex patterns that block capture when matched on query or response. */
    val blockedPatterns: Set<String> = emptySet()
)

data class RagIngestionDynamicProperties(
    /** Enable DB policy override through admin APIs. */
    val enabled: Boolean = false,

    /** Provider cache refresh interval for dynamic policy. */
    val refreshMs: Long = 10_000
)

/**
 * Response post-processing configuration.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     response:
 *       max-length: 10000
 *       filters-enabled: true
 * ```
 */
data class ResponseProperties(
    /** Maximum response length in characters. 0 = unlimited (default). */
    val maxLength: Int = 0,

    /** Enable response filter chain processing. */
    val filtersEnabled: Boolean = true
)

/**
 * Circuit breaker configuration for LLM and MCP calls.
 *
 * When enabled, tracks consecutive failures and short-circuits calls
 * when the failure rate exceeds the threshold.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     circuit-breaker:
 *       enabled: true
 *       failure-threshold: 5
 *       reset-timeout-ms: 30000
 *       half-open-max-calls: 1
 * ```
 */
data class CircuitBreakerProperties(
    /** Enable circuit breaker. Disabled by default (opt-in). */
    val enabled: Boolean = false,

    /** Number of consecutive failures before opening the circuit. */
    val failureThreshold: Int = 5,

    /** Time in ms to wait before transitioning from OPEN to HALF_OPEN. */
    val resetTimeoutMs: Long = 30_000,

    /** Number of trial calls allowed in HALF_OPEN state. */
    val halfOpenMaxCalls: Int = 1
)

/**
 * Response caching configuration.
 *
 * When enabled, identical requests return cached responses to avoid
 * redundant LLM calls. Only deterministic responses (temperature at
 * or below [cacheableTemperature]) are cached.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     cache:
 *       enabled: true
 *       max-size: 1000
 *       ttl-minutes: 60
 *       cacheable-temperature: 0.0
 * ```
 */
data class CacheProperties(
    /** Enable response caching. Disabled by default (opt-in). */
    val enabled: Boolean = false,

    /** Maximum number of cached entries. */
    val maxSize: Long = 1000,

    /** Time-to-live for cache entries in minutes. */
    val ttlMinutes: Long = 60,

    /** Only cache responses when temperature is at or below this value. */
    val cacheableTemperature: Double = 0.0
)

/**
 * Graceful degradation / fallback configuration.
 *
 * When enabled, agent execution failures trigger fallback to alternative
 * LLM models. Models are tried in order until one succeeds.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     fallback:
 *       enabled: true
 *       models:
 *         - openai
 *         - anthropic
 * ```
 */
data class FallbackProperties(
    /** Enable graceful degradation. Disabled by default (opt-in). */
    val enabled: Boolean = false,

    /** Fallback model names in priority order. */
    val models: List<String> = emptyList()
)

/**
 * Output guard configuration for post-execution response validation.
 *
 * When enabled, LLM responses are checked for PII, policy violations,
 * and custom regex patterns before being returned to the caller.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     output-guard:
 *       enabled: true
 *       pii-masking-enabled: true
 *       custom-patterns:
 *         - pattern: "(?i)internal\\s+use\\s+only"
 *           action: REJECT
 *           name: "Internal Document"
 * ```
 */
data class OutputGuardProperties(
    /** Enable output guard. Disabled by default (opt-in). */
    val enabled: Boolean = false,

    /** Enable built-in PII masking stage. */
    val piiMaskingEnabled: Boolean = true,

    /** Enable dynamic runtime-managed regex rules (admin-managed). */
    val dynamicRulesEnabled: Boolean = true,

    /** Refresh interval for dynamic rules cache (milliseconds). */
    val dynamicRulesRefreshMs: Long = 3000,

    /** Custom regex patterns for blocking or masking. */
    val customPatterns: List<OutputBlockPattern> = emptyList()
)

/**
 * Intent classification configuration.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     intent:
 *       enabled: true
 *       confidence-threshold: 0.6
 *       llm-model: gemini
 *       rule-confidence-threshold: 0.8
 * ```
 */
data class IntentProperties(
    /** Intent classification enabled (opt-in) */
    val enabled: Boolean = false,

    /** Minimum confidence to apply an intent profile */
    val confidenceThreshold: Double = 0.6,

    /** LLM provider for classification (null = use default provider) */
    val llmModel: String? = null,

    /** Minimum rule-based confidence to skip LLM fallback */
    val ruleConfidenceThreshold: Double = 0.8,

    /** Maximum few-shot examples per intent in LLM prompt */
    val maxExamplesPerIntent: Int = 3,

    /** Maximum conversation turns to include for context-aware classification */
    val maxConversationTurns: Int = 2,

    /** Intent names to block — requests classified as these intents are rejected */
    val blockedIntents: Set<String> = emptySet()
)

/**
 * Dynamic scheduler configuration.
 *
 * When enabled, allows cron-scheduled MCP tool execution managed via REST API.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     scheduler:
 *       enabled: true
 *       thread-pool-size: 5
 * ```
 */
data class SchedulerProperties(
    /** Enable dynamic scheduler. Disabled by default (opt-in). */
    val enabled: Boolean = false,

    /** Thread pool size for scheduled task execution. */
    val threadPoolSize: Int = 5
)

/**
 * Input/output boundary policy configuration.
 *
 * Provides a single config group for all length-based boundary checks:
 * input min/max, system prompt max, output min/max.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     boundaries:
 *       input-min-chars: 1
 *       input-max-chars: 5000
 *       system-prompt-max-chars: 50000
 *       output-min-chars: 0
 *       output-max-chars: 0
 *       output-min-violation-mode: warn
 * ```
 */
data class BoundaryProperties(
    /** Minimum input length in characters. */
    val inputMinChars: Int = 1,

    /** Maximum input length in characters. */
    val inputMaxChars: Int = 5000,

    /** Maximum system prompt length in characters. 0 = disabled. */
    val systemPromptMaxChars: Int = 0,

    /** Minimum output length in characters. 0 = disabled. */
    val outputMinChars: Int = 0,

    /** Maximum output length in characters. 0 = disabled. */
    val outputMaxChars: Int = 0,

    /** Policy when output is below outputMinChars. */
    val outputMinViolationMode: OutputMinViolationMode = OutputMinViolationMode.WARN
)

/**
 * Policy for handling output minimum length violations.
 */
enum class OutputMinViolationMode {
    /** Log a warning and pass the short response through. */
    WARN,

    /** Make one additional LLM call requesting a longer response. Falls back to WARN if still short. */
    RETRY_ONCE,

    /** Fail with OUTPUT_TOO_SHORT error code. */
    FAIL
}

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
    val summary: SummaryProperties = SummaryProperties()
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
