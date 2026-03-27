# Data Models & API Reference

> **Key files:** `AgentModels.kt`, `AgentErrorCode.kt`, `AgentMetrics.kt`, `ChatController.kt`
> This document describes Arc Reactor's core data models, error handling system, metrics interface, and REST API.

## Core Data Models

### AgentCommand — Agent Execution Request

This is the input model used to request work from an agent.

```kotlin
data class AgentCommand(
    val systemPrompt: String,              // System prompt (defines agent role)
    val userPrompt: String,                // User prompt (actual question/request)
    val mode: AgentMode = AgentMode.REACT, // Execution mode
    val model: String? = null,             // LLM model name override (uses config if null)
    val conversationHistory: List<Message> = emptyList(),  // Initial conversation history
    val temperature: Double? = null,       // LLM temperature (uses config value if null)
    val maxToolCalls: Int = 10,            // Maximum tool calls for this request
    val userId: String? = null,            // User ID (used by Guard, Memory)
    val metadata: Map<String, Any> = emptyMap(),  // User-defined metadata
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,  // Response format
    val responseSchema: String? = null,    // JSON/YAML schema (for structured output)
    val media: List<MediaAttachment> = emptyList()  // Multimodal attachments (images, audio, etc.)
)
```

**Field behavior:**

| Field | Used by | Notes |
|-------|---------|-------|
| `systemPrompt` | LLM system message | RAG/JSON/YAML directives are appended automatically |
| `userPrompt` | LLM user message + RAG query | Subject to Guard input validation |
| `mode` | `REACT` and `PLAN_EXECUTE` are implemented | `STANDARD`, `STREAMING` are reserved |
| `model` | LLM model selection | Overrides `AgentProperties.llm.model` for this request. `null` = use configured default |
| `temperature` | Uses `AgentProperties.llm.temperature` if `null` | Per-request override |
| `userId` | Guard (Rate Limit), Memory (session), Hook (context) | Falls back to "anonymous" if `null` |
| `metadata` | Passed to Hook context | Used for audit logs, billing, etc. |
| `responseFormat` | If `JSON`/`YAML`, appends structured output directives to system prompt | JSON/YAML not supported in streaming mode |
| `responseSchema` | Structured output schema | If provided with `JSON` or `YAML` format, appends expected schema to system prompt |
| `media` | Multimodal LLM input | Supports images, audio, video via URI or raw bytes. See [Multimodal Reference](multimodal.md) |

### AgentResult — Agent Execution Result

```kotlin
data class AgentResult(
    val success: Boolean,                          // Whether execution succeeded
    val content: String?,                          // LLM response text
    val errorCode: AgentErrorCode? = null,         // Error code (on failure)
    val errorMessage: String? = null,              // User-friendly error message
    val toolsUsed: List<String> = emptyList(),     // List of tool names used
    val tokenUsage: TokenUsage? = null,            // Token usage
    val durationMs: Long = 0,                      // Execution time (ms)
    val metadata: Map<String, Any> = emptyMap()    // Additional metadata
)
```

**Factory methods:**

```kotlin
// Success result
AgentResult.success(
    content = "Response text",
    toolsUsed = listOf("calculator", "datetime"),
    tokenUsage = TokenUsage(promptTokens = 500, completionTokens = 200),
    durationMs = 1500
)

// Failure result
AgentResult.failure(
    errorMessage = "Request timed out.",
    errorCode = AgentErrorCode.TIMEOUT,
    durationMs = 30000
)
```

### TokenUsage — Token Usage

```kotlin
data class TokenUsage(
    val promptTokens: Int,                         // Input token count
    val completionTokens: Int,                     // Output token count
    val totalTokens: Int = promptTokens + completionTokens  // Total token count
)
```

Tokens from all LLM calls within the ReAct loop are **accumulated**. If there are 3 tool calls, the tokens from 4 LLM calls are summed together.

### ResponseFormat — Response Format

```kotlin
enum class ResponseFormat {
    TEXT,   // Plain text (default)
    JSON,   // JSON response (adds directives to system prompt)
    YAML    // YAML response (adds directives to system prompt)
}
```

**Structured output mode behavior:**
- `JSON`: Appends `"You MUST respond with valid JSON only."` to the system prompt
- `YAML`: Appends `"You MUST respond with valid YAML only."` to the system prompt
- If `responseSchema` is provided, also appends `"Expected schema: {schema}"`
- Not available in streaming mode (partial fragments are meaningless)

### AgentMode — Execution Mode

```kotlin
enum class AgentMode {
    STANDARD,      // Single response (no tool calls) — not implemented
    REACT,         // ReAct loop (default)
    STREAMING,     // Streaming — reserved
    PLAN_EXECUTE   // Plan-then-execute mode
}
```

`REACT` and `PLAN_EXECUTE` are implemented. `PLAN_EXECUTE` first generates a tool call plan, then executes it sequentially. `executeStream()` operates in streaming mode regardless of `AgentMode`.

### Message — Conversation Message

```kotlin
data class Message(
    val role: MessageRole,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val media: List<MediaAttachment> = emptyList()
)

enum class MessageRole {
    SYSTEM, USER, ASSISTANT, TOOL
}
```

Used when passing initial conversation history via `AgentCommand.conversationHistory`. This is merged with history loaded from Memory and sent to the LLM. The `media` field supports attaching multimodal content (images, audio, etc.) to individual conversation messages.

---

## Error Handling System

### AgentErrorCode — Error Classification

```kotlin
enum class AgentErrorCode(val defaultMessage: String) {
    RATE_LIMITED("Rate limit exceeded. Please try again later."),
    TIMEOUT("Request timed out."),
    CONTEXT_TOO_LONG("Input is too long. Please reduce the content."),
    TOOL_ERROR("An error occurred during tool execution."),
    GUARD_REJECTED("Request rejected by guard."),
    HOOK_REJECTED("Request rejected by hook."),
    INVALID_RESPONSE("LLM returned an invalid structured response."),
    OUTPUT_GUARD_REJECTED("Response blocked by output guard."),
    OUTPUT_TOO_SHORT("Response is too short to meet quality requirements."),
    CIRCUIT_BREAKER_OPEN("Service temporarily unavailable due to repeated failures. Please try again later."),
    BUDGET_EXHAUSTED("Token budget exhausted. Response may be incomplete."),
    PLAN_VALIDATION_FAILED("Plan validation failed. The plan contains invalid or unauthorized tools."),
    UNKNOWN("An unknown error occurred.")
}
```

**Trigger conditions by error code:**

| Code | Trigger Condition | Retryable |
|------|-------------------|-----------|
| `RATE_LIMITED` | Guard Rate Limit or LLM 429 response | Only LLM 429 is retried |
| `TIMEOUT` | `withTimeout()` exceeded or LLM timeout | Only LLM is retried |
| `CONTEXT_TOO_LONG` | LLM context length exceeded error | Not retried |
| `TOOL_ERROR` | Exception during tool execution | Not retried |
| `GUARD_REJECTED` | Guard pipeline Reject | Not retried |
| `HOOK_REJECTED` | BeforeAgentStart/BeforeToolCall Hook Reject | Not retried |
| `INVALID_RESPONSE` | LLM returns invalid JSON/YAML when structured output is requested | Not retried |
| `OUTPUT_GUARD_REJECTED` | Output guard pipeline blocks the response | Not retried |
| `OUTPUT_TOO_SHORT` | Response fails minimum length boundary policy | Not retried |
| `CIRCUIT_BREAKER_OPEN` | Circuit breaker is open due to repeated LLM failures | Not retried |
| `BUDGET_EXHAUSTED` | StepBudgetTracker determined token budget is spent | Not retried |
| `PLAN_VALIDATION_FAILED` | PLAN_EXECUTE mode plan contains invalid or unauthorized tools | Not retried |
| `UNKNOWN` | Unclassifiable exception | Not retried |

### classifyError — Keyword-Based Error Classification

```kotlin
private fun classifyError(e: Exception): AgentErrorCode {
    val message = e.message?.lowercase() ?: ""
    return when {
        "rate limit" in message  → RATE_LIMITED
        "timeout" in message     → TIMEOUT
        "context length" in message → CONTEXT_TOO_LONG
        "tool" in message        → TOOL_ERROR
        else                     → UNKNOWN
    }
}
```

Errors are classified by keywords in the error message. Since error message formats vary across LLM providers, classification may not always be accurate.

### ErrorMessageResolver — Error Message Localization

```kotlin
fun interface ErrorMessageResolver {
    fun resolve(code: AgentErrorCode, originalMessage: String?): String
}
```

**Default implementation (English):**

```kotlin
class DefaultErrorMessageResolver : ErrorMessageResolver {
    override fun resolve(code: AgentErrorCode, originalMessage: String?): String {
        return when (code) {
            AgentErrorCode.TOOL_ERROR ->
                if (originalMessage != null) "${code.defaultMessage}: $originalMessage"
                else code.defaultMessage
            else -> code.defaultMessage
        }
    }
}
```

**Custom example (Korean):**

```kotlin
@Bean
fun errorMessageResolver() = ErrorMessageResolver { code, originalMessage ->
    when (code) {
        AgentErrorCode.RATE_LIMITED -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
        AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
        AgentErrorCode.CONTEXT_TOO_LONG -> "입력이 너무 깁니다. 내용을 줄여주세요."
        AgentErrorCode.TOOL_ERROR -> "도구 실행 중 오류: $originalMessage"
        AgentErrorCode.GUARD_REJECTED -> "요청이 거부되었습니다."
        AgentErrorCode.HOOK_REJECTED -> "요청이 거부되었습니다."
        AgentErrorCode.UNKNOWN -> "알 수 없는 오류가 발생했습니다."
    }
}
```

When registered as a `@Bean`, the default implementation is replaced via `@ConditionalOnMissingBean`.

### Complete Error Flow

```
Exception thrown
    │
    ▼
classifyError(e) → AgentErrorCode
    │
    ▼
errorMessageResolver.resolve(code, e.message) → User-facing message
    │
    ▼
AgentResult.failure(errorMessage, errorCode, durationMs)
    │
    ▼
ChatResponse(success=false, errorMessage=...)
```

---

## Observability

### AgentMetrics Interface

```kotlin
interface AgentMetrics {
    fun recordExecution(result: AgentResult)
    fun recordToolCall(toolName: String, durationMs: Long, success: Boolean)
    fun recordGuardRejection(stage: String, reason: String)
    // ... plus many more methods with default implementations
}
```

The 3 methods above are abstract. All other methods have default no-op implementations for backward compatibility. See the full interface in the [Metrics Reference](metrics.md).

**Default implementation:** `NoOpAgentMetrics` is auto-registered when no `MeterRegistry` is on the classpath. When Micrometer is available, `MicrometerAgentMetrics` is auto-registered instead via `@ConditionalOnBean(MeterRegistry::class)`. Users can always override with a custom `AgentMetrics` bean.

**When each method is called:**

| Method | Called when | Data |
|--------|------------|------|
| `recordExecution` | After `executeInternal()` completes | success, durationMs, toolsUsed, tokenUsage |
| `recordToolCall` | After individual tool execution completes | toolName, durationMs, success |
| `recordGuardRejection` | When Guard rejects | stageName, reason |

For the complete list of metric methods, see the [Metrics Reference](metrics.md).

---

## REST API

### POST /api/chat — Standard Response

Returns the complete response at once.

**Request:**

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "What is 3 + 5?",
    "userId": "user-1"
  }'
```

**ChatRequest:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `message` | String | **Required** | User message (`@NotBlank`) |
| `model` | String? | Optional | LLM provider override (uses configured default if omitted) |
| `systemPrompt` | String? | Optional | System prompt (uses default if omitted) |
| `personaId` | String? | Optional | Persona ID for system prompt resolution |
| `promptTemplateId` | String? | Optional | Prompt template ID for system prompt resolution |
| `userId` | String? | Optional | User ID |
| `metadata` | Map<String, Any>? | Optional | Additional metadata |
| `responseFormat` | ResponseFormat? | Optional | `TEXT` or `JSON` |
| `responseSchema` | String? | Optional | JSON schema (for JSON mode) |

**ChatResponse:**

| Field | Type | Description |
|-------|------|-------------|
| `content` | String? | AI response text (null on failure) |
| `success` | Boolean | Whether execution succeeded |
| `model` | String? | Model used for generation |
| `toolsUsed` | List<String> | Tools invoked during execution |
| `durationMs` | Long? | Total execution time in milliseconds |
| `errorMessage` | String? | Error message if failed |
| `errorCode` | String? | Structured error code (e.g. `TIMEOUT`, `GUARD_REJECTED`) |
| `grounded` | Boolean? | Whether response is grounded in retrieved sources |
| `verifiedSourceCount` | Int? | Number of verified sources used |
| `blockReason` | String? | Guard block reason if blocked |
| `metadata` | Map<String, Any>? | Additional metadata |

**Response example (success):**

```json
{
  "content": "3 + 5 = 8.",
  "success": true,
  "model": "gemini-2.5-flash",
  "toolsUsed": ["calculator"],
  "durationMs": 1500,
  "errorMessage": null,
  "errorCode": null,
  "grounded": null,
  "verifiedSourceCount": null,
  "blockReason": null,
  "metadata": {}
}
```

**Response example (failure):**

```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.5-flash",
  "toolsUsed": [],
  "durationMs": 120,
  "errorMessage": "Rate limit exceeded. Please try again later.",
  "errorCode": "RATE_LIMITED",
  "grounded": null,
  "verifiedSourceCount": null,
  "blockReason": null,
  "metadata": {}
}
```

### POST /api/chat/stream — Streaming Response

Returns real-time token-level responses via SSE (Server-Sent Events).

**Request:**

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello", "userId": "user-1"}'
```

**Response:** `Content-Type: text/event-stream`

```
data:Hello
data:! How
data: can I
data: help
data: you?
```

**On error:**

```
data:[error] Request timed out.
```

**Internal conversion:** `Flow<String>` → `Flux<String>` (`flow.asFlux()`)

### Default System Prompt

If `systemPrompt` is not specified, the following default is used:

```
You are a helpful AI assistant. You can use tools when needed.
Answer in the same language as the user's message.
```

### ChatRequest → AgentCommand Conversion

```kotlin
AgentCommand(
    systemPrompt = resolveSystemPrompt(request),  // personaId > promptTemplateId > systemPrompt > default
    userPrompt = request.message,
    model = request.model,
    userId = resolveUserId(exchange, request.userId),
    metadata = resolveMetadata(request, exchange),
    responseFormat = request.responseFormat ?: ResponseFormat.TEXT,
    responseSchema = request.responseSchema,
    media = resolveMediaUrls(request.mediaUrls)
)
```

`ChatRequest` is a subset of `AgentCommand`. The system prompt is resolved with priority: `personaId` > `promptTemplateId` > `systemPrompt` > default persona > fallback. Fields such as `mode`, `temperature`, `maxToolCalls`, and `conversationHistory` cannot be set via the REST API and use their default values. All options are available when calling `AgentExecutor` programmatically.
