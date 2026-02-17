# ReAct Loop Internal Implementation

> **Key file:** `SpringAiAgentExecutor.kt` (~1,730 lines)
> This document explains the internal workings of the ReAct loop, the core execution engine of Arc Reactor.

## Overall Execution Flow

```
User Request (AgentCommand)
    │
    ▼
┌─────────────────────────────┐
│  execute() / executeStream()│  Entry points (2 types)
│  ├─ MDC setup               │
│  ├─ Concurrency semaphore   │
│  └─ withTimeout applied     │
└──────────┬──────────────────┘
           │
           ▼
┌─────────────────────────────┐
│  executeInternal()          │  8-stage pipeline
│  1. Guard check             │
│  2. BeforeAgentStart Hook   │
│  3. Load conversation history│
│  4. RAG context retrieval   │
│  5. Tool selection & setup  │
│  6. executeWithTools()      │  ← ReAct loop body
│  7. Save conversation history│
│  8. AfterAgentComplete Hook │
└─────────────────────────────┘
```

## Entry Points: execute() vs executeStream()

### execute() — Non-streaming

```kotlin
override suspend fun execute(command: AgentCommand): AgentResult
```

1. Sets `runId`, `userId`, `sessionId` in MDC (structured logging)
2. `concurrencySemaphore.withPermit { }` — limits concurrent execution count
3. `withTimeout(requestTimeoutMs)` — overall request timeout
4. Calls `executeInternal()`
5. On failure, `classifyError(e)` returns `AgentResult.failure()`
6. `CancellationException` must always be rethrown (to preserve structured concurrency)

### executeStream() — Streaming

```kotlin
override fun executeStream(command: AgentCommand): Flow<String>
```

Follows the same 8 stages as non-streaming, with the following differences:

| Difference | execute() | executeStream() |
|--------|-----------|-----------------|
| Return type | `AgentResult` | `Flow<String>` |
| LLM call | `requestSpec.call()` | `requestSpec.stream().chatResponse()` |
| Content collection | All at once from final response | `emit()` per chunk |
| Memory save | `saveHistory()` | `saveStreamingHistory()` (finally block) |
| Error delivery | AgentResult.failure() | `emit("[error] message")` |

**Key point about streaming memory save:**

```kotlin
// Saved in a finally block outside of withTimeout
finally {
    if (streamSuccess) {
        conversationManager.saveStreamingHistory(command, lastIterationContent.toString())
    }
}
```

Only `lastIterationContent` is saved. Rather than the entire accumulated content (`collectedContent`), only the content from the last ReAct iteration is stored in the conversation history.

## ReAct Loop Body: executeWithTools()

```kotlin
private suspend fun executeWithTools(
    command: AgentCommand,
    tools: List<Any>,
    conversationHistory: List<Message>,
    hookContext: HookContext,
    toolsUsed: MutableList<String>,
    ragContext: String? = null
): AgentResult
```

### Loop Structure

```
while (true) {
    1. trimMessagesToFitContext()     — trim messages to fit within token budget
    2. chatClient.prompt() setup     — system + messages + options + tools
    3. callWithRetry { call() }      — LLM call (with retry)
    4. Accumulate token usage
    5. Detect tool calls
       ├─ None → return AgentResult.success() (exit loop)
       └─ Present → continue
    6. Add AssistantMessage          — includes tool call info
    7. executeToolCallsInParallel()  — parallel tool execution
    8. Add ToolResponseMessage       — tool results
    9. Check maxToolCalls
       ├─ Not reached → continue loop
       └─ Reached → activeTools = emptyList() (remove tools)
}
```

### Tool Call Detection

```kotlin
val assistantOutput = chatResponse?.results?.firstOrNull()?.output
val pendingToolCalls = assistantOutput?.toolCalls.orEmpty()

if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
    // Return final response
    return AgentResult.success(content = response.content() ?: "")
}
```

If the LLM response's `output.toolCalls` is empty, it is treated as the final text response.

### maxToolCalls Forced Termination

```kotlin
if (totalToolCalls >= maxToolCalls) {
    activeTools = emptyList()           // Remove tools
    chatOptions = buildChatOptions(command, false)  // Options without tools
}
```

Replacing tools with an empty list forces the next LLM call to generate a final text response without any tool calls. Simply logging a message would cause the LLM to keep attempting tool calls, so **removing the tools entirely** is the critical technique.

## Parallel Tool Execution

### Execution Pattern

```kotlin
private suspend fun executeToolCallsInParallel(
    toolCalls: List<AssistantMessage.ToolCall>,
    ...
): List<ToolResponseMessage.ToolResponse> = coroutineScope {
    toolCalls.map { toolCall ->
        async {
            executeSingleToolCall(toolCall, ...)
        }
    }.awaitAll()  // Order preserved (by map index order)
}
```

- `coroutineScope` — structured concurrency (if one fails, all are cancelled)
- `map { async { } }` — starts all tool calls concurrently
- `awaitAll()` — returns results in original order

### Single Tool Execution Flow

```kotlin
private suspend fun executeSingleToolCall(...): ToolResponseMessage.ToolResponse {
    // 1. Check maxToolCalls with AtomicInteger (concurrency-safe)
    val currentCount = totalToolCallsCounter.getAndIncrement()
    if (currentCount >= maxToolCalls) return error response

    // 2. BeforeToolCall Hook
    hookExecutor?.executeBeforeToolCall(toolCallContext)
    // → On Reject, only that specific tool is skipped

    // 3. Execute tool
    val adapter = findToolAdapter(toolName, tools)
    if (adapter != null) {
        toolsUsed.add(toolName)        // Only added after confirming adapter exists
        adapter.call(toolCall.arguments())
    } else {
        "Error: Tool '$toolName' not found"  // Prevents LLM hallucination
    }

    // 4. AfterToolCall Hook
    hookExecutor?.executeAfterToolCall(context, result)

    // 5. Record metrics
    agentMetrics.recordToolCall(toolName, durationMs, success)
}
```

**Note:** `toolsUsed.add(toolName)` is only called after confirming that the adapter exists. This is because the LLM may generate (hallucinate) tool names that do not exist.

## Context Window Management

### Token Budget Calculation

```
budget = maxContextWindowTokens - systemPromptTokens - maxOutputTokens
```

Example: `128000 - 2000 - 4096 = 121904 tokens`

### 2-Phase Trimming

```kotlin
private fun trimMessagesToFitContext(messages: MutableList<Message>, systemPrompt: String)
```

**Phase 1: Remove old history (from the front)**

```
[History messages...] [Current UserMessage] [Tool interactions...]
 ↑ Removal starts here
```

Removes messages starting from the oldest. Stops when it reaches the **last UserMessage (current prompt)**.

**Phase 2: Remove tool interactions (after UserMessage)**

If Phase 1 is insufficient, removes tool call/response pairs that come after the current UserMessage.

### Message Pair Integrity

```kotlin
private fun calculateRemoveGroupSize(messages: List<Message>): Int {
    val first = messages[0]
    // Remove AssistantMessage(toolCalls) → ToolResponseMessage as a pair
    if (first is AssistantMessage && !first.toolCalls.isNullOrEmpty()) {
        return if (messages[1] is ToolResponseMessage) 2 else 1
    }
    return 1
}
```

`AssistantMessage(toolCalls)` and `ToolResponseMessage` must always be removed as a pair. If only one remains, it will cause an LLM API error.

### Token Estimation

```kotlin
private fun estimateMessageTokens(message: Message): Int {
    return when (message) {
        is UserMessage -> tokenEstimator.estimate(message.text)
        is AssistantMessage -> {
            val textTokens = tokenEstimator.estimate(message.text ?: "")
            val toolCallTokens = message.toolCalls.sumOf {
                tokenEstimator.estimate(it.name() + it.arguments())
            }
            textTokens + toolCallTokens
        }
        is ToolResponseMessage -> message.responses.sumOf {
            tokenEstimator.estimate(it.responseData())
        }
        // ...
    }
}
```

`TokenEstimator` is CJK-aware:
- Latin characters: ~4 chars/token
- Korean/CJK: ~1.5 chars/token
- Emoji: ~1 char/token

## LLM Retry (callWithRetry)

### Exponential Backoff + Jitter

```kotlin
private suspend fun <T> callWithRetry(block: suspend () -> T): T {
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e  // Never retry
        } catch (e: Exception) {
            if (!isTransientError(e) || last attempt) throw e

            // Exponential backoff: 1s → 2s → 4s (max 10s)
            val baseDelay = min(initialDelay * 2^attempt, maxDelay)
            // ±25% jitter: distributes concurrent retries
            val jitter = baseDelay * 0.25 * random(-1, 1)
            delay(baseDelay + jitter)
        }
    }
}
```

### Transient Error Detection

Retry targets:
- Rate limit (429)
- Timeout
- 5xx server errors
- Connection errors

Immediate failure:
- Authentication errors
- Context too long
- Invalid request
- `CancellationException` (coroutine cancellation — must never be retried)

## Concurrency Semaphore

```kotlin
concurrencySemaphore.withPermit {
    withTimeout(properties.concurrency.requestTimeoutMs) {
        executeInternal(command, hookContext, toolsUsed, startTime)
    }
}
```

`Semaphore(permits = maxConcurrentRequests)` limits the number of concurrent agent executions. When the limit is exceeded, coroutines will suspend and wait.

## Error Classification

```kotlin
private fun classifyError(e: Exception): AgentErrorCode {
    return when {
        "rate limit" in message  → RATE_LIMITED
        "timeout" in message     → TIMEOUT
        "context length" in msg  → CONTEXT_TOO_LONG
        "tool" in message        → TOOL_ERROR
        else                     → UNKNOWN
    }
}
```

Classification is based on error messages, which are then converted to user-friendly messages through `ErrorMessageResolver`.

## System Prompt Construction

```kotlin
private fun buildSystemPrompt(
    basePrompt: String,
    ragContext: String?,
    responseFormat: ResponseFormat,
    responseSchema: String?
): String {
    val parts = mutableListOf(basePrompt)

    // Append RAG context
    if (ragContext != null) {
        parts.add("[Retrieved Context]\n$ragContext")
    }

    // Append JSON mode directive
    if (responseFormat == ResponseFormat.JSON) {
        parts.add("[Response Format]\nYou MUST respond with valid JSON only.")
        if (responseSchema != null) {
            parts.add("Expected JSON schema:\n$responseSchema")
        }
    }

    return parts.joinToString("\n\n")
}
```

Final system prompt structure:

```
{User-defined system prompt}

[Retrieved Context]
{Documents retrieved via RAG}

[Response Format]
You MUST respond with valid JSON only.
Expected JSON schema: {...}
```

## Tool Selection and Preparation

```kotlin
private fun selectAndPrepareTools(userPrompt: String): List<Any> {
    // 1. LocalTool (@Tool annotation-based)
    val localToolInstances = localTools.toList()

    // 2. Collect ToolCallback + MCP tools
    val allCallbacks = toolCallbacks + mcpToolCallbacks()

    // 3. Filter via ToolSelector (based on user prompt)
    val selectedCallbacks = toolSelector?.select(userPrompt, allCallbacks)
        ?: allCallbacks

    // 4. Wrap with ArcToolCallbackAdapter
    val wrappedCallbacks = selectedCallbacks.map { ArcToolCallbackAdapter(it) }

    // 5. Apply maxToolsPerRequest
    return (localToolInstances + wrappedCallbacks).take(maxToolsPerRequest)
}
```

## ArcToolCallbackAdapter

An adapter that bridges Spring AI's `ToolCallback` and Arc Reactor's `ToolCallback`:

```kotlin
internal class ArcToolCallbackAdapter(
    val arcCallback: ToolCallback
) : org.springframework.ai.tool.ToolCallback {

    override fun call(toolInput: String): String {
        val args = parseJsonToMap(toolInput)
        // suspend fun → blocking conversion (Spring AI interface constraint)
        return runBlocking(Dispatchers.IO) {
            arcCallback.call(args)?.toString() ?: ""
        }
    }
}
```

`runBlocking(Dispatchers.IO)` is used because the Spring AI interface only supports a synchronous `call()`. This is a known constraint.

## CancellationException Rule

In every `suspend fun`, `CancellationException` must be caught and rethrown before catching a generic `Exception`:

```kotlin
try {
    // Business logic
} catch (e: CancellationException) {
    throw e  // Preserve structured concurrency
} catch (e: Exception) {
    // Error handling
}
```

This pattern applies to **all** suspend functions including `execute()`, `executeWithTools()`, `callWithRetry()`, and others. If omitted, `withTimeout` will not function correctly.

> `java.util.concurrent.CancellationException` is a typealias for `kotlin.coroutines.cancellation.CancellationException`.
