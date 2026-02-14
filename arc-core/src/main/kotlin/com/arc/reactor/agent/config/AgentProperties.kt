package com.arc.reactor.agent.config

import com.arc.reactor.guard.output.impl.OutputBlockPattern
import com.arc.reactor.mcp.model.McpTransportType
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

    /** MCP configuration */
    val mcp: McpConfigProperties = McpConfigProperties(),

    /** Webhook configuration */
    val webhook: WebhookConfigProperties = WebhookConfigProperties(),

    /** Tool selection configuration */
    val toolSelection: ToolSelectionProperties = ToolSelectionProperties(),

    /** Human-in-the-Loop approval configuration */
    val approval: ApprovalProperties = ApprovalProperties(),

    /** Tool policy configuration (e.g., read-only vs write tools) */
    val toolPolicy: ToolPolicyProperties = ToolPolicyProperties(),

    /** Multimodal (file upload / media URL) configuration */
    val multimodal: MultimodalProperties = MultimodalProperties(),

    /** Response post-processing configuration */
    val response: ResponseProperties = ResponseProperties(),

    /** Circuit breaker configuration */
    val circuitBreaker: CircuitBreakerProperties = CircuitBreakerProperties(),

    /** Response caching configuration */
    val cache: CacheProperties = CacheProperties(),

    /** Graceful degradation / fallback configuration */
    val fallback: FallbackProperties = FallbackProperties(),

    /** Intent classification configuration */
    val intent: IntentProperties = IntentProperties(),

    /** Output guard configuration */
    val outputGuard: OutputGuardProperties = OutputGuardProperties()
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
    val requestTimeoutMs: Long = 30000,

    /** Per-tool call timeout (milliseconds) */
    val toolCallTimeoutMs: Long = 15000
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

/**
 * MCP configuration — server declarations and security settings.
 *
 * Servers declared here are auto-registered on startup.
 * Additional servers can be managed at runtime via REST API.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     mcp:
 *       servers:
 *         - name: swagger-agent
 *           transport: sse
 *           url: http://localhost:8081/sse
 *       security:
 *         allowed-server-names: []
 *         max-tool-output-length: 50000
 * ```
 */
data class McpConfigProperties(
    /** MCP servers to register on startup */
    val servers: List<McpServerDefinition> = emptyList(),

    /** Security settings */
    val security: McpSecurityProperties = McpSecurityProperties(),

    /** MCP connection timeout (milliseconds) */
    val connectionTimeoutMs: Long = 30_000,

    /** Auto-reconnection settings */
    val reconnection: McpReconnectionProperties = McpReconnectionProperties()
)

/**
 * MCP server definition for yml-based registration.
 */
data class McpServerDefinition(
    /** Unique server name */
    val name: String = "",

    /** Transport type */
    val transport: McpTransportType = McpTransportType.SSE,

    /** SSE/HTTP endpoint URL */
    val url: String? = null,

    /** STDIO command */
    val command: String? = null,

    /** STDIO command arguments */
    val args: List<String> = emptyList(),

    /** Description */
    val description: String? = null,

    /** Auto-connect on startup */
    val autoConnect: Boolean = true
)

data class McpSecurityProperties(
    /** Allowed MCP server names (empty = allow all) */
    val allowedServerNames: Set<String> = emptySet(),

    /** Maximum tool output length in characters */
    val maxToolOutputLength: Int = 50_000
)

/**
 * MCP auto-reconnection configuration.
 *
 * When enabled, failed MCP server connections are automatically retried
 * with exponential backoff. On-demand reconnection is also attempted
 * when a tool call targets a disconnected/failed server.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     mcp:
 *       reconnection:
 *         enabled: true
 *         max-attempts: 5
 *         initial-delay-ms: 5000
 *         multiplier: 2.0
 *         max-delay-ms: 60000
 * ```
 */
data class McpReconnectionProperties(
    /** Enable auto-reconnection for failed MCP servers. */
    val enabled: Boolean = true,

    /** Maximum reconnection attempts before giving up. */
    val maxAttempts: Int = 5,

    /** Initial delay between reconnection attempts (milliseconds). */
    val initialDelayMs: Long = 5000,

    /** Backoff multiplier for subsequent attempts. */
    val multiplier: Double = 2.0,

    /** Maximum delay between reconnection attempts (milliseconds). */
    val maxDelayMs: Long = 60_000
)

/**
 * Webhook notification configuration.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     webhook:
 *       enabled: true
 *       url: https://example.com/webhook
 *       timeout-ms: 5000
 *       include-conversation: false
 * ```
 */
data class WebhookConfigProperties(
    /** Enable webhook notifications */
    val enabled: Boolean = false,

    /** POST target URL */
    val url: String = "",

    /** HTTP timeout (milliseconds) */
    val timeoutMs: Long = 5000,

    /** Whether to include full conversation in payload */
    val includeConversation: Boolean = false
)

/**
 * Tool selection strategy configuration.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     tool-selection:
 *       strategy: semantic    # all | keyword | semantic
 *       similarity-threshold: 0.3
 *       max-results: 10
 * ```
 */
data class ToolSelectionProperties(
    /** Selection strategy: "all", "keyword", or "semantic" */
    val strategy: String = "all",

    /** Minimum cosine similarity threshold for semantic selection */
    val similarityThreshold: Double = 0.3,

    /** Maximum number of tools to return from semantic selection */
    val maxResults: Int = 10
)

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

    /** Maximum context tokens */
    val maxContextTokens: Int = 4000
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
    val maxConversationTurns: Int = 2
)
