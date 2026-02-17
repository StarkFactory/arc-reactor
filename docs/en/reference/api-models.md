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
    val conversationHistory: List<Message> = emptyList(),  // Initial conversation history
    val temperature: Double? = null,       // LLM temperature (uses config value if null)
    val maxToolCalls: Int = 10,            // Maximum tool calls for this request
    val userId: String? = null,            // User ID (used by Guard, Memory)
    val metadata: Map<String, Any> = emptyMap(),  // User-defined metadata
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,  // Response format
    val responseSchema: String? = null     // JSON schema (for JSON mode)
)
```

**Field behavior:**

| Field | Used by | Notes |
|-------|---------|-------|
| `systemPrompt` | LLM system message | RAG/JSON directives are appended automatically |
| `userPrompt` | LLM user message + RAG query | Subject to Guard input validation |
| `mode` | Only `REACT` is currently implemented | `STANDARD`, `STREAMING` are reserved |
| `temperature` | Uses `AgentProperties.llm.temperature` if `null` | Per-request override |
| `userId` | Guard (Rate Limit), Memory (session), Hook (context) | Falls back to "anonymous" if `null` |
| `metadata` | Passed to Hook context | Used for audit logs, billing, etc. |
| `responseFormat` | If `JSON`, appends JSON directives to system prompt | JSON not supported in streaming mode |

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
    JSON    // JSON response (adds directives to system prompt)
}
```

**JSON mode behavior:**
- Appends `"You MUST respond with valid JSON only."` to the system prompt
- If `responseSchema` is provided, also appends `"Expected JSON schema: {schema}"`
- Not available in streaming mode (JSON fragments are meaningless)

### AgentMode — Execution Mode

```kotlin
enum class AgentMode {
    STANDARD,   // Single response (no tool calls) — not implemented
    REACT,      // ReAct loop (default)
    STREAMING   // Streaming — reserved
}
```

Currently only `REACT` is implemented. `executeStream()` operates in streaming mode regardless of `AgentMode`.

### Message — Conversation Message

```kotlin
data class Message(
    val role: MessageRole,
    val content: String,
    val timestamp: Instant = Instant.now()
)

enum class MessageRole {
    SYSTEM, USER, ASSISTANT, TOOL
}
```

Used when passing initial conversation history via `AgentCommand.conversationHistory`. This is merged with history loaded from Memory and sent to the LLM.

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
}
```

**Default implementation:** `NoOpAgentMetrics` (does nothing)

**When each method is called:**

| Method | Called when | Data |
|--------|------------|------|
| `recordExecution` | After `executeInternal()` completes | success, durationMs, toolsUsed, tokenUsage |
| `recordToolCall` | After individual tool execution completes | toolName, durationMs, success |
| `recordGuardRejection` | When Guard rejects | stageName, reason |

### Micrometer Implementation Example

```kotlin
class MicrometerAgentMetrics(private val registry: MeterRegistry) : AgentMetrics {
    private val executionCounter = registry.counter("arc.agent.executions")
    private val executionTimer = registry.timer("arc.agent.execution.duration")
    private val errorCounter = registry.counter("arc.agent.errors")
    private val toolCounter = registry.counter("arc.agent.tool.calls")

    override fun recordExecution(result: AgentResult) {
        executionCounter.increment()
        executionTimer.record(result.durationMs, TimeUnit.MILLISECONDS)
        if (!result.success) errorCounter.increment()
    }

    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {
        toolCounter.increment()
        registry.timer("arc.agent.tool.duration", "tool", toolName)
            .record(durationMs, TimeUnit.MILLISECONDS)
        if (!success) {
            registry.counter("arc.agent.tool.errors", "tool", toolName).increment()
        }
    }

    override fun recordGuardRejection(stage: String, reason: String) {
        registry.counter("arc.agent.guard.rejections", "stage", stage).increment()
    }
}
```

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
| `systemPrompt` | String? | Optional | System prompt (uses default if omitted) |
| `userId` | String? | Optional | User ID |
| `metadata` | Map<String, Any>? | Optional | Additional metadata |
| `responseFormat` | ResponseFormat? | Optional | `TEXT` or `JSON` |
| `responseSchema` | String? | Optional | JSON schema (for JSON mode) |

**ChatResponse:**

| Field | Type | Description |
|-------|------|-------------|
| `content` | String? | AI response text (null on failure) |
| `success` | Boolean | Whether execution succeeded |
| `toolsUsed` | List<String> | List of tools used |
| `errorMessage` | String? | Error message (on failure) |

**Response example (success):**

```json
{
  "content": "3 + 5 = 8.",
  "success": true,
  "toolsUsed": ["calculator"],
  "errorMessage": null
}
```

**Response example (failure):**

```json
{
  "content": null,
  "success": false,
  "toolsUsed": [],
  "errorMessage": "Rate limit exceeded. Please try again later."
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
    systemPrompt = request.systemPrompt ?: DEFAULT_SYSTEM_PROMPT,
    userPrompt = request.message,
    userId = request.userId,
    metadata = request.metadata ?: emptyMap(),
    responseFormat = request.responseFormat ?: ResponseFormat.TEXT,
    responseSchema = request.responseSchema
)
```

`ChatRequest` is a subset of `AgentCommand`. Fields such as `mode`, `temperature`, `maxToolCalls`, and `conversationHistory` cannot be set via the REST API and use their default values. All options are available when calling `AgentExecutor` programmatically.
