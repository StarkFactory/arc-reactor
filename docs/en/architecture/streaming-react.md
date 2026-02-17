# Streaming ReAct Loop

## What Is This?

The existing `execute()` waits until the LLM response is fully complete, then returns the result all at once.
`executeStream()` delivers output to the user in real time, as the LLM generates each token.

**Key change**: Previously, streaming simply passed through raw text, but now it also **detects and executes Tool calls**.

```
[Before] User → LLM streaming → Pass through text as-is (no tool calls possible)
[After]  User → LLM streaming → Detect tools → Execute tools → Stream again → Final response
```

---

## Before / After Comparison

### Before (Previous executeStream)

```kotlin
// Simple pass-through: just streams raw text
val flux = requestSpec.stream().content()  // Flux<String>
emitAll(flux.asFlow())
```

**Problems:**
- No tool call detection (no ReAct loop)
- No Semaphore concurrency control
- No Timeout
- BeforeToolCallHook / AfterToolCallHook not invoked
- Conversation history not saved
- AfterAgentComplete Hook not invoked
- Metrics not recorded

### After (New executeStream)

```kotlin
// Streaming with structured ChatResponse
val flux = requestSpec.stream().chatResponse()  // Flux<ChatResponse>

flux.asFlow().collect { chunk ->
    // Text → deliver in real time
    chunk.result?.output?.text?.let { emit(it) }
    // Tool calls → detect and store
    chunk.result?.output?.toolCalls?.let { pendingToolCalls = it }
}

// If tool calls detected → execute → stream again (ReAct loop)
```

**What was fixed:**
- Tool call detection + execution (full ReAct loop)
- Semaphore concurrency control
- Timeout applied
- BeforeToolCallHook / AfterToolCallHook working correctly
- Conversation history automatically saved
- AfterAgentComplete Hook working correctly
- Metrics properly recorded
- MDC logging context

---

## Execution Flow

```
User: "Tell me the weather in Seoul"
          |
          v
    +---------------+
    |  Guard Check  | <- Rate Limit, Injection Detection, etc.
    +------+--------+
           | (passed)
           v
    +---------------+
    |  Before Hook  | <- BeforeAgentStartHook
    +------+--------+
           | (passed)
           v
    +-------------------------------------+
    |       Streaming ReAct Loop          |
    |                                     |
    |  1. LLM streaming starts            |
    |     "Let me check the weather"      |
    |     --> emit()                      |  <- Delivered to user in real time!
    |                                     |
    |  2. Tool call detected              |
    |     weather_tool({"city":"Seoul"})  |
    |                                     |
    |  3. BeforeToolCallHook executed     |
    |     (dangerous tools blocked here)  |
    |                                     |
    |  4. Tool executed                   |
    |     -> "Clear, 25C"                 |
    |                                     |
    |  5. AfterToolCallHook executed      |
    |     (logging, auditing, etc.)       |
    |                                     |
    |  6. Tool result added to            |
    |     conversation                    |
    |                                     |
    |  7. LLM streams again              |
    |     "Seoul is currently clear       |
    |      and 25C"                       |
    |     --> emit()                      |  <- Delivered to user in real time!
    |                                     |
    |  8. No tool calls -> loop ends      |
    +------------------+------------------+
                       |
                       v
    +-------------------------------------+
    |  Save conversation history          |
    |  Execute AfterAgentComplete Hook    |
    |  Record Metrics                     |
    +-------------------------------------+
```

---

## Examples from the User's Perspective

### Example 1: Web Chat (SSE)

```kotlin
@GetMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
suspend fun chatStream(@RequestParam message: String): Flow<String> {
    return agentExecutor.executeStream(
        AgentCommand(
            systemPrompt = "You are a weather assistant.",
            userPrompt = message,
            userId = "user-123",
            metadata = mapOf("sessionId" to "session-abc")
        )
    )
}
```

**What the user sees on screen:**
```
[Typing in real time...] "Let me check the weather"
[Brief pause - tool executing]
[Typing in real time...] "Seoul is currently clear and 25C. A great day to go outside!"
```

### Example 2: Slack Bot

Slack supports message updates, which pairs well with streaming:

```kotlin
val chunks = StringBuilder()
agentExecutor.executeStream(command).collect { chunk ->
    chunks.append(chunk)
    slackClient.updateMessage(channelId, messageTs, chunks.toString())
}
```

**How it appears in Slack:**
```
Bot: Let me check the weather  <- Appears as if typing progressively
Bot: Let me check the weather Seoul is currently clear and 25C.  <- Continues typing after tool execution
```

### Example 3: Using Multiple Tools

```
User: "Tell me the weather in Seoul and the current time"

[Streaming] "Let me check both for you."
[Tool execution: weather("Seoul") -> "Clear 25C"]
[Tool execution: time("KST") -> "3:00 PM"]
[Streaming] "Seoul is currently clear and 25C, and the time in Korea is 3:00 PM."
```

---

## execute() vs executeStream() Comparison

| Item | execute() | executeStream() |
|------|-----------|-----------------|
| Return type | `AgentResult` | `Flow<String>` |
| Response method | All at once after completion | Real-time chunk-by-chunk |
| ReAct loop | O | O |
| Guard check | O | O |
| Before/After Hook | O | O |
| Tool Hook | O | O |
| maxToolCalls | O | O |
| Semaphore | O | O |
| Timeout | O | O |
| Conversation history save | O | O |
| Metrics recording | O | O |
| MDC logging | O | O |
| Token usage tracking | O | X (due to streaming nature) |

---

## SSE Event Types

The streaming endpoint `POST /api/chat/stream` emits typed Server-Sent Events:

| Event Type | Data | Description |
|------------|------|-------------|
| `message` | Text chunk | LLM-generated token (main content) |
| `tool_start` | Tool name | Tool execution has started |
| `tool_end` | Tool name | Tool execution has completed |
| `error` | Error message | An error occurred (guard rejection, timeout, LLM failure, etc.) |
| `done` | Empty | Stream is complete |

### Error Events

Errors are emitted as typed `event: error` SSE events instead of inline text. This allows clients to handle errors programmatically:

```javascript
const eventSource = new EventSource('/api/chat/stream');

eventSource.addEventListener('message', (e) => {
    appendToChat(e.data);  // Normal text chunk
});

eventSource.addEventListener('tool_start', (e) => {
    showToolIndicator(e.data);  // e.data = tool name
});

eventSource.addEventListener('error', (e) => {
    showErrorBanner(e.data);  // e.data = error message
});

eventSource.addEventListener('done', () => {
    eventSource.close();
});
```

Error events are emitted for:
- Guard rejections (rate limit, input validation, injection detection)
- Hook rejections (custom business logic)
- Structured output format rejection (JSON/YAML not supported in streaming)
- Request timeout
- LLM provider failures

### Client Cancellation

When the client closes the SSE connection, the server automatically cancels the ongoing execution:

- The Kotlin coroutine Flow is cancelled via structured concurrency
- Tool executions in progress receive `CancellationException`
- Resources are properly cleaned up in the `finally` block
- Cancellation is logged at DEBUG level with the userId

No explicit cancellation API is needed — simply closing the HTTP connection is sufficient.

---

## Technical Implementation Details

### 1. Using `stream().chatResponse()`

```kotlin
// Before: receives text only
requestSpec.stream().content()       // Flux<String>

// After: receives structured ChatResponse
requestSpec.stream().chatResponse()  // Flux<ChatResponse>
```

`ChatResponse` contains not only text but also tool call information.
In Spring AI 1.1.2, tool calls can be detected using the `hasToolCalls()` method.

### 2. internalToolExecutionEnabled = false

```kotlin
ToolCallingChatOptions.builder()
    .internalToolExecutionEnabled(false)  // Disable Spring AI's automatic execution
    .build()
```

We disable Spring AI's built-in tool execution and manage the loop ourselves.
This is necessary to invoke Hooks and enforce maxToolCalls.

### 3. Simultaneous Real-Time Text + Tool Detection

```kotlin
flux.asFlow().collect { chunk ->
    // If text is present, deliver to user immediately
    val text = chunk.result?.output?.text
    if (!text.isNullOrEmpty()) {
        emit(text)  // <- Real time!
    }

    // If tool call info is present, store it (usually in the last chunk)
    val toolCalls = chunk.result?.output?.toolCalls
    if (!toolCalls.isNullOrEmpty()) {
        pendingToolCalls = toolCalls
    }
}
```

### 4. Semaphore + Timeout

```kotlin
concurrencySemaphore.withPermit {      // Limit concurrent requests
    withTimeout(requestTimeoutMs) {     // Overall time limit
        // ... streaming ReAct loop
    }
}
```

The same concurrency controls as `execute()` are applied to streaming as well.

---

## Test Coverage

A total of **18 TDD tests** added (9 existing tests updated):

| Category | Tests | Description |
|----------|-------|-------------|
| Tool calls | 5 | Detection, execution, multiple tools, mode-specific behavior |
| Hook | 3 | BeforeTool, AfterTool, rejection handling |
| maxToolCalls | 1 | Stops when limit exceeded |
| Timeout | 1 | Timeout error handling |
| AfterComplete | 4 | Hook, Metrics, tool list inclusion |
| Memory | 2 | History save (text, after tool use) |
| Error handling | 2 | Tool failure, unregistered tool |

---

## File Changes

| File | Changes |
|------|---------|
| `SpringAiAgentExecutor.kt` | Full rewrite of `executeStream()` (ReAct loop) |
| `StreamingTest.kt` | Mock changed from `content()` to `chatResponse()` |
| `StreamingReActTest.kt` | New - 18 TDD tests |
