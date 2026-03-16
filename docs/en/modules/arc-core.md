# arc-core

## Overview

`arc-core` is the heart of the Arc Reactor framework. It contains the ReAct (Reasoning + Acting) agent executor, the 5-stage guard pipeline, the hook lifecycle system, the tool abstraction layer, all Spring Boot auto-configuration, and supporting subsystems for memory, RAG, multi-agent orchestration, Prompt Lab, and resilience.

Use `arc-core` whenever you need the agent runtime itself. The module is designed to work standalone (no HTTP layer required); `arc-web` adds the REST/SSE interface on top.

**Request flow:**

```
Guard → Hook(BeforeAgentStart) → ReAct Loop (LLM ↔ Tool)* → Hook(AfterAgentComplete) → Response
```

---

## Key Components

| Class | Role | Package |
|---|---|---|
| `SpringAiAgentExecutor` | Core ReAct loop — wraps LLM calls, tool dispatch, guard/hook execution | `agent.impl` |
| `AgentExecutor` | Public interface: `execute()` and `executeStream()` | `agent` |
| `AgentCommand` | Input DTO: prompt, mode, model, metadata, media | `agent.model` |
| `AgentResult` | Output DTO: content, errorCode, toolsUsed, tokenUsage | `agent.model` |
| `AgentProperties` | All `arc.reactor.*` configuration properties | `agent.config` |
| `ArcReactorAutoConfiguration` | Spring Boot auto-configuration entrypoint | `autoconfigure` |
| `RequestGuard` / `GuardStage` | 5-stage fail-close guardrail pipeline | `guard` |
| `AgentHook` / `BeforeAgentStartHook` / `AfterAgentCompleteHook` | Lifecycle extension points (fail-open by default) | `hook` |
| `ToolCallback` | Framework-agnostic tool interface | `tool` |
| `LocalTool` | Spring `@Tool`-annotated tool class (schema auto-generated) | `tool` |
| `ConversationManager` | Loads and saves session history; optional hierarchical memory | `memory` |
| `MemoryStore` | Session persistence interface (in-memory or JDBC) | `memory` |
| `McpManager` | Dynamic MCP server lifecycle management | `mcp` |
| `RagPipeline` | Retrieval-Augmented Generation — query → retrieve → rerank | `rag` |
| `IntentClassifier` | Rule-based + LLM intent classification | `intent` |
| `CircuitBreaker` | LLM/MCP failure protection | `resilience` |
| `OutputGuardPipeline` | Post-execution response validation (PII, regex, dynamic rules) | `guard.output` |
| `ExperimentOrchestrator` | Prompt Lab — A/B experiment runner | `promptlab` |
| `SupervisorOrchestrator` | Multi-agent supervisor pattern | `agent.multi` |
| `DynamicSchedulerService` | Cron-scheduled MCP tool and agent execution | `scheduler` |
| `CreateScheduledJobTool` | Agent tool — create scheduled jobs via natural language | `scheduler.tool` |
| `ListScheduledJobsTool` | Agent tool — list all scheduled jobs | `scheduler.tool` |
| `UpdateScheduledJobTool` | Agent tool — partial update of scheduled jobs | `scheduler.tool` |
| `DeleteScheduledJobTool` | Agent tool — delete scheduled jobs by ID or name | `scheduler.tool` |

---

## Configuration

All properties are bound under the `arc.reactor` prefix. Defaults are sourced directly from `AgentProperties.kt` and `AgentPolicyAndFeatureProperties.kt`.

### LLM (`arc.reactor.llm`)

| Property | Default | Description |
|---|---|---|
| `default-provider` | `gemini` | Default LLM provider name |
| `temperature` | `0.1` | Sampling temperature |
| `max-output-tokens` | `4096` | Maximum tokens per response |
| `max-context-window-tokens` | `128000` | Context window size for message trimming |
| `max-conversation-turns` | `10` | History turns kept per request |
| `top-p` | `null` (provider default) | Nucleus sampling parameter |
| `frequency-penalty` | `null` (provider default) | Frequency penalty |
| `presence-penalty` | `null` (provider default) | Presence penalty |
| `google-search-retrieval-enabled` | `false` | Gemini search grounding (opt-in) |
| `prompt-caching.enabled` | `false` | Anthropic prompt caching (opt-in) |
| `prompt-caching.provider` | `anthropic` | Provider to apply caching for |
| `prompt-caching.cache-system-prompt` | `true` | Mark system prompt for caching |
| `prompt-caching.cache-tools` | `true` | Mark tool definitions for caching |
| `prompt-caching.min-cacheable-tokens` | `1024` | Minimum tokens before caching |

### Guard (`arc.reactor.guard`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Master switch |
| `rate-limit-per-minute` | `20` | Max requests per user per minute |
| `rate-limit-per-hour` | `200` | Max requests per user per hour |
| `injection-detection-enabled` | `true` | Prompt injection heuristic detection |
| `unicode-normalization-enabled` | `true` | NFKC normalization + zero-width strip |
| `max-zero-width-ratio` | `0.1` | Rejection threshold for zero-width chars |
| `classification-enabled` | `false` | Rule-based content classification |
| `classification-llm-enabled` | `false` | LLM-based classification (requires classification-enabled) |
| `canary-token-enabled` | `false` | System prompt leakage detection |
| `canary-seed` | `arc-reactor-canary` | Canary token seed (override per deployment) |
| `tool-output-sanitization-enabled` | `false` | Sanitize tool output before LLM |
| `audit-enabled` | `true` | Guard audit trail |
| `topic-drift-enabled` | `false` | Crescendo attack defense |

### Concurrency (`arc.reactor.concurrency`)

| Property | Default | Description |
|---|---|---|
| `max-concurrent-requests` | `20` | Semaphore permits |
| `request-timeout-ms` | `30000` | Full request timeout |
| `tool-call-timeout-ms` | `15000` | Per-tool call timeout |

### Retry (`arc.reactor.retry`)

| Property | Default | Description |
|---|---|---|
| `max-attempts` | `3` | Maximum retry attempts |
| `initial-delay-ms` | `200` | First retry delay |
| `multiplier` | `2.0` | Exponential backoff multiplier |
| `max-delay-ms` | `10000` | Cap on retry delay |

### Tool Selection (`arc.reactor.tool-selection`)

| Property | Default | Description |
|---|---|---|
| `strategy` | `all` | `all`, `keyword`, or `semantic` |
| `similarity-threshold` | `0.3` | Cosine similarity floor for semantic selection |
| `max-results` | `10` | Max tools returned by semantic selection |

### Limits (`arc.reactor`)

| Property | Default | Description |
|---|---|---|
| `max-tool-calls` | `10` | Max ReAct loop iterations per request |
| `max-tools-per-request` | `30` | Max tools visible to LLM per request |

### Boundaries (`arc.reactor.boundaries`)

| Property | Default | Description |
|---|---|---|
| `input-min-chars` | `1` | Minimum input length |
| `input-max-chars` | `10000` | Maximum input length |
| `system-prompt-max-chars` | `50000` | Maximum system prompt length |
| `output-min-chars` | `0` (disabled) | Minimum response length |
| `output-max-chars` | `0` (disabled) | Maximum response length |
| `output-min-violation-mode` | `WARN` | `WARN`, `RETRY_ONCE`, or `FAIL` |

### RAG (`arc.reactor.rag`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Master switch |
| `similarity-threshold` | `0.65` | Vector search threshold |
| `top-k` | `5` | Search results to retrieve |
| `rerank-enabled` | `false` | Re-ranking after retrieval |
| `query-transformer` | `passthrough` | `passthrough`, `hyde`, or `decomposition` |
| `max-context-tokens` | `4000` | Injected context token budget |
| `retrieval-timeout-ms` | `3000` | Retrieval timeout to prevent thread-pool exhaustion |

#### Hybrid Search (`arc.reactor.rag.hybrid`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | BM25 + vector hybrid search (opt-in) |
| `bm25-weight` | `0.5` | RRF weight for BM25 ranks |
| `vector-weight` | `0.5` | RRF weight for vector search ranks |
| `rrf-k` | `60.0` | RRF smoothing constant |
| `bm25-k1` | `1.5` | BM25 term-frequency saturation |
| `bm25-b` | `0.75` | BM25 length normalization |

#### Chunking (`arc.reactor.rag.chunking`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Document chunking (opt-in) |
| `chunk-size` | `512` | Target chunk size in tokens |
| `min-chunk-size-chars` | `350` | Minimum chunk size in characters |
| `min-chunk-threshold` | `512` | Documents at or below this token count are not split |
| `overlap` | `50` | Overlap tokens between adjacent chunks |
| `keep-separator` | `true` | Preserve paragraph/sentence separators |
| `max-num-chunks` | `100` | Maximum chunks per document |

#### Parent Retrieval (`arc.reactor.rag.parent-retrieval`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Parent document retrieval (opt-in) |
| `window-size` | `1` | Adjacent chunks to include before/after each hit |

#### Ingestion (`arc.reactor.rag.ingestion`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Ingestion candidate capture |
| `require-review` | `true` | Admin review before vector ingestion |
| `allowed-channels` | `[]` | Channels for auto-capture (empty = all) |
| `min-query-chars` | `10` | Minimum query length |
| `min-response-chars` | `20` | Minimum response length |
| `dynamic.enabled` | `false` | DB-backed policy override |
| `dynamic.refresh-ms` | `10000` | Policy cache refresh interval |

#### Compression (`arc.reactor.rag.compression`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Contextual compression (RECOMP) |
| `min-content-length` | `200` | Documents shorter than this skip compression |

#### Adaptive Routing (`arc.reactor.rag.adaptive-routing`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Skip RAG for simple queries (Adaptive-RAG) |
| `timeout-ms` | `3000` | Classification timeout |
| `complex-top-k` | `15` | topK override for COMPLEX queries |

### Memory (`arc.reactor.memory`)

#### Summary (`arc.reactor.memory.summary`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Hierarchical memory summarization |
| `trigger-message-count` | `20` | Message count before summarization |
| `recent-message-count` | `10` | Recent messages kept verbatim |
| `llm-model` | `null` (default provider) | LLM for summarization |
| `max-narrative-tokens` | `500` | Narrative summary token budget |

#### User Memory (`arc.reactor.memory.user`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Per-user long-term memory (opt-in) |
| `inject-into-prompt` | `false` | Inject user memory into system prompt |
| `max-prompt-injection-chars` | `1000` | Max characters for injected memory context |
| `max-recent-topics` | `10` | Recent topics retained per user |
| `jdbc.table-name` | `user_memories` | DB table for user memory records |

### Circuit Breaker (`arc.reactor.circuit-breaker`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Opt-in |
| `failure-threshold` | `5` | Consecutive failures before opening |
| `reset-timeout-ms` | `30000` | Open → Half-Open transition delay |
| `half-open-max-calls` | `1` | Trial calls in Half-Open state |

### Cache (`arc.reactor.cache`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Response caching (opt-in) |
| `max-size` | `1000` | Max cached entries |
| `ttl-minutes` | `60` | Cache entry TTL |
| `cacheable-temperature` | `0.0` | Only cache at or below this temperature |
| `semantic.enabled` | `false` | Redis semantic cache fallback (opt-in) |
| `semantic.similarity-threshold` | `0.92` | Minimum cosine similarity for semantic hits |
| `semantic.max-candidates` | `50` | Semantic candidates evaluated per lookup |
| `semantic.max-entries-per-scope` | `1000` | Redis semantic entries retained per scope |
| `semantic.key-prefix` | `arc:cache` | Redis key namespace prefix |

### Fallback (`arc.reactor.fallback`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Model fallback chain (opt-in) |
| `models` | `[]` | Ordered fallback providers |

### Output Guard (`arc.reactor.output-guard`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Post-execution response validation |
| `pii-masking-enabled` | `true` | Built-in PII masking stage |
| `dynamic-rules-enabled` | `true` | Admin-managed regex rules |
| `dynamic-rules-refresh-ms` | `3000` | Rule cache refresh interval |

### Intent (`arc.reactor.intent`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Intent classification (opt-in) |
| `confidence-threshold` | `0.6` | Minimum confidence for profile application |
| `rule-confidence-threshold` | `0.8` | Threshold to skip LLM fallback |
| `max-examples-per-intent` | `3` | Few-shot examples per intent |
| `max-conversation-turns` | `2` | Conversation turns for context-aware classification |
| `blocked-intents` | `[]` | Intents that reject the request |

### Approval / HITL (`arc.reactor.approval`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Human-in-the-Loop approval (opt-in) |
| `timeout-ms` | `300000` | Approval timeout (5 minutes) |
| `resolved-retention-ms` | `604800000` | Retention for resolved approvals (7 days) |
| `tool-names` | `[]` | Tools requiring approval |

### Tool Policy (`arc.reactor.tool-policy`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Tool policy enforcement |
| `write-tool-names` | `[]` | Side-effecting tool names |
| `deny-write-channels` | `[slack]` | Channels where write tools are blocked |
| `allow-write-tool-names-in-deny-channels` | `[]` | Write tools allowed even in deny channels |
| `allow-write-tool-names-by-channel` | `{}` | Channel-scoped allowlist for deny channels |
| `deny-write-message` | (default string) | Message when tool is denied |
| `dynamic.enabled` | `false` | DB-backed dynamic policy (admin API) |
| `dynamic.refresh-ms` | `10000` | Dynamic policy cache refresh interval |

### Multimodal (`arc.reactor.multimodal`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | File upload and media URL support |
| `max-file-size-bytes` | `10485760` | 10 MB per file |
| `max-files-per-request` | `5` | Max files per multipart request |

### Prompt Lab (`arc.reactor.prompt-lab`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Prompt Lab feature |
| `max-concurrent-experiments` | `3` | Parallel experiment limit |
| `max-queries-per-experiment` | `100` | Max test queries per experiment |
| `max-versions-per-experiment` | `10` | Max prompt versions per experiment |
| `max-repetitions` | `5` | Max repetitions per version-query pair |
| `candidate-count` | `3` | Candidate prompts to auto-generate |
| `min-negative-feedback` | `5` | Feedback threshold for auto-pipeline |
| `experiment-timeout-ms` | `600000` | Experiment execution timeout |
| `schedule.enabled` | `false` | Scheduled auto-optimization |
| `schedule.cron` | `0 0 2 * * *` | Cron expression (daily at 2 AM) |

### Scheduler (`arc.reactor.scheduler`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Dynamic cron scheduler |
| `thread-pool-size` | `5` | Scheduler thread pool |
| `default-timezone` | System default | Default timezone for jobs |
| `default-execution-timeout-ms` | `300000` | Default job execution timeout |
| `max-executions-per-job` | `100` | Execution history entries retained per job |

When `enabled=true`, four agent tools are auto-registered (`@ConditionalOnMissingBean`):

| Tool | Description |
|---|---|
| `create_scheduled_job` | Create a new AGENT-mode scheduled job (LLM converts natural language to cron) |
| `list_scheduled_jobs` | List all jobs with status, cron, timezone, and last execution result |
| `update_scheduled_job` | Partial update — change cron, prompt, Slack channel, timezone, or enable/disable |
| `delete_scheduled_job` | Delete a job by ID or name |

All tools have `category = null` (always loaded regardless of user prompt keywords) and return JSON error strings on failure (never throw).

**Cost note:** AGENT-mode schedules invoke the LLM on each execution. Consider cost implications when creating high-frequency schedules.

**Security note:** `update_scheduled_job` can modify `agentPrompt`, changing what a job executes on future runs. In multi-user environments, enable `ToolApprovalPolicy` to require human approval before prompt modifications.

### MCP (`arc.reactor.mcp`)

| Property | Default | Description |
|---|---|---|
| `connection-timeout-ms` | `30000` | MCP connection timeout |
| `allow-private-addresses` | `false` | Allow connections to private/loopback IPs |
| `security.max-tool-output-length` | `50000` | Tool output character limit |
| `security.allowed-server-names` | `[]` | Allowed MCP server names (empty = all) |
| `security.allowed-stdio-commands` | `[npx, node, python, ...]` | Allowed STDIO command executables |
| `reconnection.enabled` | `true` | Auto-reconnect failed MCP servers |
| `reconnection.max-attempts` | `5` | Reconnection attempt limit |
| `reconnection.initial-delay-ms` | `5000` | First reconnection delay |
| `reconnection.multiplier` | `2.0` | Backoff multiplier |
| `reconnection.max-delay-ms` | `60000` | Maximum reconnection delay |

### Tool Result Cache (`arc.reactor.tool-result-cache`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Cache identical tool invocations within a ReAct loop (opt-in) |
| `ttl-seconds` | `60` | Time-to-live for cached entries |
| `max-size` | `200` | Maximum cached entries |

### Citation (`arc.reactor.citation`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Citation auto-formatting (opt-in) |
| `format` | `markdown` | Citation format |

### Webhook (`arc.reactor.webhook`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Webhook notifications (opt-in) |
| `url` | `""` | POST target URL |
| `timeout-ms` | `5000` | HTTP timeout |
| `include-conversation` | `false` | Include full conversation in payload |

### Tool Enrichment (`arc.reactor.tool-enrichment`)

| Property | Default | Description |
|---|---|---|
| `requester-aware-tool-names` | `[]` | Tools that receive the caller's identity automatically |

### CORS (`arc.reactor.cors`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | CORS support (opt-in) |
| `allowed-origins` | `[http://localhost:3000]` | Allowed origins |
| `allowed-methods` | `[GET, POST, PUT, DELETE, OPTIONS]` | Allowed HTTP methods |
| `allowed-headers` | `[*]` | Allowed headers |
| `allow-credentials` | `false` | Allow cookies/Authorization |
| `max-age` | `3600` | Preflight cache duration (seconds) |

### Security Headers (`arc.reactor.security-headers`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Security headers injection |

### Response (`arc.reactor.response`)

| Property | Default | Description |
|---|---|---|
| `max-length` | `0` (unlimited) | Maximum response length in characters |
| `filters-enabled` | `true` | Response filter chain processing |

### Tracing (`arc.reactor.tracing`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Span emission (no-op tracer when OTel absent) |
| `service-name` | `arc-reactor` | Service name in span attributes |
| `include-user-id` | `false` | Include user ID in spans (PII risk) |

---

## Extension Points

### ToolCallback — Custom Tool

Implement `ToolCallback` directly for full control over the schema:

```kotlin
@Component
class OrderStatusTool(
    private val orderService: OrderService
) : ToolCallback {
    override val name = "get_order_status"
    override val description = "Returns the current status of an order"
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "orderId": { "type": "string", "description": "Order ID" }
          },
          "required": ["orderId"]
        }
    """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        val orderId = arguments["orderId"] as? String
            ?: return "Error: orderId is required"
        return orderService.getStatus(orderId) ?: "Error: order not found"
    }
}
```

Key rules:
- Return `"Error: ..."` strings on failure. Do NOT throw exceptions from `call()`.
- Use `override val timeoutMs: Long? = 5000` to override the global tool timeout for this tool.

### LocalTool — Spring @Tool Annotation

Use `LocalTool` when you want Spring to generate the JSON schema from method signatures:

```kotlin
@Component
class WeatherTool(
    private val weatherApi: WeatherApiClient
) : LocalTool {
    override val category = DefaultToolCategory.SEARCH

    @Tool(description = "Get current weather for a city")
    fun getWeather(
        @ToolParam(description = "City name") city: String
    ): String {
        return weatherApi.getCurrent(city) ?: "Weather data unavailable"
    }
}
```

### GuardStage — Custom Guard Stage

Built-in stages use orders 1–5. Use order 10+ for custom stages:

```kotlin
@Component
class BusinessHoursGuard : GuardStage {
    override val stageName = "BusinessHours"
    override val order = 15  // after InputValidation(2), before InjectionDetection(3)?

    override suspend fun check(command: GuardCommand): GuardResult {
        val hour = java.time.LocalTime.now().hour
        if (hour < 9 || hour >= 18) {
            return GuardResult.Rejected(
                reason = "Service is only available 09:00–18:00",
                category = RejectionCategory.UNAUTHORIZED,
                stage = stageName
            )
        }
        return GuardResult.Allowed.DEFAULT
    }
}
```

Guard is always **fail-close**: any stage error blocks the request.

### Hook — Lifecycle Extension

Hooks are **fail-open** by default (errors are logged and execution continues). Set `failOnError = true` for critical hooks.

```kotlin
@Component
class AuditHook(
    private val auditRepository: AuditRepository
) : AfterAgentCompleteHook {
    override val order = 100

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        try {
            auditRepository.save(
                AuditRecord(
                    runId = context.runId,
                    userId = context.userId,
                    success = response.success,
                    toolsUsed = response.toolsUsed,
                    durationMs = context.durationMs()
                )
            )
        } catch (e: CancellationException) {
            throw e  // MUST rethrow CancellationException
        } catch (e: Exception) {
            logger.error(e) { "Audit save failed" }
            // fail-open: error is swallowed, execution continues
        }
    }
}
```

Hook order ranges:
- `1–99`: Critical/early (auth, security)
- `100–199`: Standard (logging, audit)
- `200+`: Late (cleanup, notifications)

Available hook types:

| Interface | When called | Can reject? |
|---|---|---|
| `BeforeAgentStartHook` | Before LLM call | Yes (`HookResult.Reject`) |
| `BeforeToolCallHook` | Before each tool execution | Yes (`HookResult.Reject`) |
| `AfterToolCallHook` | After each tool execution | No |
| `AfterAgentCompleteHook` | After agent completes (success or failure) | No |

### ErrorMessageResolver — Custom Error Messages

Override error messages (e.g., for localization):

```kotlin
@Bean
fun errorMessageResolver() = ErrorMessageResolver { code, originalMessage ->
    when (code) {
        AgentErrorCode.RATE_LIMITED -> "Too many requests. Please try again in a moment."
        AgentErrorCode.TIMEOUT -> "The request timed out. Please try again."
        AgentErrorCode.GUARD_REJECTED -> "Your request could not be processed."
        else -> code.defaultMessage
    }
}
```

### Bean Overrides

All beans registered by `ArcReactorAutoConfiguration` use `@ConditionalOnMissingBean`. Declare your own bean to replace any default:

```kotlin
@Bean
fun memoryStore(): MemoryStore = MyCustomMemoryStore()

@Bean
fun requestGuard(stages: List<GuardStage>): RequestGuard = MyCustomGuard(stages)
```

---

## Code Examples

### Minimal Agent Execution

```kotlin
@Service
class ChatService(private val agentExecutor: AgentExecutor) {

    suspend fun chat(userId: String, sessionId: String, message: String): String {
        val result = agentExecutor.execute(
            AgentCommand(
                systemPrompt = "You are a helpful assistant.",
                userPrompt = message,
                userId = userId,
                metadata = mapOf("sessionId" to sessionId)
            )
        )
        return if (result.success) result.content ?: "" else "Error: ${result.errorMessage}"
    }
}
```

### Streaming Agent Execution

```kotlin
@Service
class StreamingChatService(private val agentExecutor: AgentExecutor) {

    fun stream(userId: String, message: String): Flow<String> {
        val command = AgentCommand(
            systemPrompt = "You are a helpful assistant.",
            userPrompt = message,
            userId = userId
        )
        return agentExecutor.executeStream(command)
    }
}
```

### Structured JSON Output

```kotlin
val result = agentExecutor.execute(
    AgentCommand(
        systemPrompt = "Extract data from the text.",
        userPrompt = "John Doe, age 30, lives in Seoul.",
        responseFormat = ResponseFormat.JSON,
        responseSchema = """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "age": { "type": "integer" },
                "city": { "type": "string" }
              }
            }
        """.trimIndent()
    )
)
```

If the LLM returns invalid JSON, the executor automatically makes one repair call before returning `INVALID_RESPONSE`.

---

## Common Pitfalls

**CancellationException must always be rethrown.** Every `suspend fun` that catches `Exception` broadly must rethrow `CancellationException` first. Failing to do so breaks Kotlin structured concurrency.

```kotlin
// Correct
try {
    doWork()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.error(e) { "Work failed" }
}
```

**ToolCallback must return errors as strings, not throw exceptions.** Exceptions from tools propagate as `TOOL_ERROR` and can disrupt the ReAct loop.

**maxToolCalls enforcement.** When the loop reaches `maxToolCalls`, the executor sets `activeTools = emptyList()`. If you only log at this point without clearing tools, the LLM keeps calling tools indefinitely.

**AssistantMessage constructor is protected.** Always use the builder:

```kotlin
AssistantMessage.builder().content("text").toolCalls(calls).build()
```

**Do not declare provider API keys with empty defaults in `application.yml`.** Use environment variables only: `GEMINI_API_KEY`, `SPRING_AI_OPENAI_API_KEY`, `SPRING_AI_ANTHROPIC_API_KEY`. Empty defaults leak to logs and override real values with blank strings.

**`.forEach {}` in coroutines creates a non-suspend lambda.** Use `for` loops instead when calling suspend functions inside iteration.

**MCP servers are registered only via REST API.** Do not add MCP server URLs to `application.yml` or create MCP configuration classes. Use `POST /api/mcp/servers`.

**Guard is fail-close; Hook is fail-open.** Security logic belongs in Guard, not Hook.
