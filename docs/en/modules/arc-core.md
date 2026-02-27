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
| `DynamicSchedulerService` | Cron-scheduled MCP tool execution | `scheduler` |

---

## Configuration

All properties are bound under the `arc.reactor` prefix. Defaults are sourced directly from `AgentProperties.kt` and `AgentPolicyAndFeatureProperties.kt`.

### LLM (`arc.reactor.llm`)

| Property | Default | Description |
|---|---|---|
| `default-provider` | `gemini` | Default LLM provider name |
| `temperature` | `0.3` | Sampling temperature |
| `max-output-tokens` | `4096` | Maximum tokens per response |
| `max-context-window-tokens` | `128000` | Context window size for message trimming |
| `max-conversation-turns` | `10` | History turns kept per request |
| `google-search-retrieval-enabled` | `false` | Gemini search grounding (opt-in) |

### Guard (`arc.reactor.guard`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Master switch |
| `rate-limit-per-minute` | `10` | Max requests per user per minute |
| `rate-limit-per-hour` | `100` | Max requests per user per hour |
| `injection-detection-enabled` | `true` | Prompt injection heuristic detection |
| `unicode-normalization-enabled` | `true` | NFKC normalization + zero-width strip |
| `max-zero-width-ratio` | `0.1` | Rejection threshold for zero-width chars |
| `classification-enabled` | `false` | Rule-based content classification |
| `classification-llm-enabled` | `false` | LLM-based classification (requires classification-enabled) |
| `canary-token-enabled` | `false` | System prompt leakage detection |
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
| `initial-delay-ms` | `1000` | First retry delay |
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
| `max-tools-per-request` | `20` | Max tools visible to LLM per request |

### Boundaries (`arc.reactor.boundaries`)

| Property | Default | Description |
|---|---|---|
| `input-min-chars` | `1` | Minimum input length |
| `input-max-chars` | `5000` | Maximum input length |
| `system-prompt-max-chars` | `0` (disabled) | Maximum system prompt length |
| `output-min-chars` | `0` (disabled) | Minimum response length |
| `output-max-chars` | `0` (disabled) | Maximum response length |
| `output-min-violation-mode` | `WARN` | `WARN`, `RETRY_ONCE`, or `FAIL` |

### RAG (`arc.reactor.rag`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Master switch |
| `similarity-threshold` | `0.7` | Vector search threshold |
| `top-k` | `10` | Search results to retrieve |
| `rerank-enabled` | `true` | Re-ranking after retrieval |
| `query-transformer` | `passthrough` | `passthrough` or `hyde` |
| `max-context-tokens` | `4000` | Injected context token budget |

### Memory Summary (`arc.reactor.memory.summary`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Hierarchical memory summarization |
| `trigger-message-count` | `20` | Message count before summarization |
| `recent-message-count` | `10` | Recent messages kept verbatim |
| `llm-model` | `null` (default provider) | LLM for summarization |
| `max-narrative-tokens` | `500` | Narrative summary token budget |

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
| `blocked-intents` | `[]` | Intents that reject the request |

### Approval / HITL (`arc.reactor.approval`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Human-in-the-Loop approval (opt-in) |
| `timeout-ms` | `300000` | Approval timeout (5 minutes) |
| `tool-names` | `[]` | Tools requiring approval |

### Tool Policy (`arc.reactor.tool-policy`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Tool policy enforcement |
| `write-tool-names` | `[]` | Side-effecting tool names |
| `deny-write-channels` | `[slack]` | Channels where write tools are blocked |
| `deny-write-message` | (default string) | Message when tool is denied |

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
| `min-negative-feedback` | `5` | Feedback threshold for auto-pipeline |
| `experiment-timeout-ms` | `600000` | Experiment execution timeout |

### Scheduler (`arc.reactor.scheduler`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Dynamic cron scheduler |
| `thread-pool-size` | `5` | Scheduler thread pool |

### MCP (`arc.reactor.mcp`)

| Property | Default | Description |
|---|---|---|
| `connection-timeout-ms` | `30000` | MCP connection timeout |
| `security.max-tool-output-length` | `50000` | Tool output character limit |
| `reconnection.enabled` | `true` | Auto-reconnect failed MCP servers |
| `reconnection.max-attempts` | `5` | Reconnection attempt limit |
| `reconnection.initial-delay-ms` | `5000` | First reconnection delay |
| `reconnection.multiplier` | `2.0` | Backoff multiplier |
| `reconnection.max-delay-ms` | `60000` | Maximum reconnection delay |

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
