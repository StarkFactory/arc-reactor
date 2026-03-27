# ReAct Loop Internal Implementation

> **Key file:** `SpringAiAgentExecutor.kt` (~626 lines, orchestrator that delegates to extracted helper classes)
> This document explains the internal workings of the ReAct loop, the core execution engine of Arc Reactor.

## Overall Execution Flow

```
User Request (AgentCommand)
    │
    ▼
┌─────────────────────────────┐
│  execute() / executeStream()│  Entry points (2 types)
│  ├─ Run context setup       │
│  ├─ Concurrency semaphore   │
│  └─ withTimeout applied     │
└──────────┬──────────────────┘
           │
           ▼
┌──────────────────────────────────┐
│  AgentExecutionCoordinator       │  Multi-stage pipeline
│  1. Guard check                  │  (PreExecutionResolver)
│  2. BeforeAgentStart Hook        │  (PreExecutionResolver)
│  3. Intent resolution            │  (PreExecutionResolver)
│  4. Response cache lookup        │
│  5. Load conversation history    │
│  6. RAG context retrieval        │  (RagContextRetriever)
│  7. Tool selection & setup       │  (ToolPreparationPlanner)
│  8. executeWithTools()           │  ← ReAct loop body (ManualReActLoopExecutor)
│  9. Output guard + boundaries    │  (ExecutionResultFinalizer)
│  10. Save conversation history   │  (ExecutionResultFinalizer)
│  11. AfterAgentComplete Hook     │  (ExecutionResultFinalizer)
└──────────────────────────────────┘
```

## Entry Points: execute() vs executeStream()

### execute() — Non-streaming

```kotlin
override suspend fun execute(command: AgentCommand): AgentResult
```

1. Opens a run context (`AgentRunContextManager`) for structured logging
2. `concurrencySemaphore.withPermit { }` — limits concurrent execution count
3. `withTimeout(requestTimeoutMs)` — overall request timeout
4. Delegates to `AgentExecutionCoordinator.execute()`
5. On failure, `AgentErrorPolicy.classify(e)` returns `AgentResult.failure()`
6. `CancellationException` must always be rethrown (via `throwIfCancellation()`)

### executeStream() — Streaming

```kotlin
override fun executeStream(command: AgentCommand): Flow<String>
```

Follows the same stages as non-streaming, with the following differences:

| Difference | execute() | executeStream() |
|--------|-----------|-----------------|
| Return type | `AgentResult` | `Flow<String>` |
| LLM call | `requestSpec.call()` | `requestSpec.stream().chatResponse()` |
| Content collection | All at once from final response | `emit()` per chunk |
| Memory save | `saveHistory()` | `saveStreamingHistory()` (finally block) |
| Error delivery | AgentResult.failure() | `emit("[error] message")` |
| Coordinator | `AgentExecutionCoordinator` | `StreamingExecutionCoordinator` |
| Finalizer | `ExecutionResultFinalizer` | `StreamingCompletionFinalizer` |

**Key point about streaming memory save:**

```kotlin
// StreamingCompletionFinalizer.finalize() runs in the finally block
if (streamSuccess) {
    conversationManager.saveStreamingHistory(command, lastIterationContent)
}
```

Only `lastIterationContent` is saved. Rather than the entire accumulated content (`collectedContent`), only the content from the last ReAct iteration is stored in the conversation history.

## ReAct Loop Body: ManualReActLoopExecutor

The ReAct loop is implemented in `ManualReActLoopExecutor.execute()`. The entry point `SpringAiAgentExecutor.executeWithTools()` delegates to it after resolving the system prompt and chat client:

```kotlin
// ManualReActLoopExecutor.execute()
suspend fun execute(
    command: AgentCommand,
    activeChatClient: ChatClient,
    systemPrompt: String,
    initialTools: List<Any>,
    conversationHistory: List<Message>,
    hookContext: HookContext,
    toolsUsed: MutableList<String>,
    allowedTools: Set<String>?,
    maxToolCalls: Int
): AgentResult
```

### Loop Structure

```
while (true) {
    1. messageTrimmer.trim()          — trim messages to fit within token budget
    2. buildRequestSpec()             — system + messages + options + tools
    3. callWithRetry { call() }       — LLM call (with retry + circuit breaker)
    4. Accumulate token usage
    5. Detect tool calls
       ├─ None → validateAndRepairResponse() (exit loop)
       └─ Present → continue
    6. Add AssistantMessage           — includes tool call info
    7. toolCallOrchestrator
         .executeInParallel()         — parallel tool execution
    8. Add ToolResponseMessage        — tool results
    9. Check maxToolCalls
       ├─ Not reached → continue loop
       └─ Reached → activeTools = emptyList() + add SystemMessage nudge
}
```

### Tool Call Detection

```kotlin
val assistantOutput = chatResponse?.results?.firstOrNull()?.output
val pendingToolCalls = assistantOutput?.toolCalls.orEmpty()

if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
    return validateAndRepairResponse(assistantOutput?.text.orEmpty(), ...)
}
```

If the LLM response's `output.toolCalls` is empty, it is treated as the final text response. The response is then validated (and optionally repaired for structured output formats) by `StructuredResponseRepairer`.

### maxToolCalls Forced Termination

```kotlin
if (totalToolCalls >= maxToolCalls) {
    activeTools = emptyList()           // Remove tools
    chatOptions = buildChatOptions(command, false)  // Options without tools
    messages.add(SystemMessage(
        "Tool call limit reached ($totalToolCalls/$maxToolCalls). " +
            "Summarize the results you have so far and provide your best answer. " +
            "Do not request additional tool calls."
    ))
}
```

Replacing tools with an empty list forces the next LLM call to generate a final text response without any tool calls. Simply logging a message would cause the LLM to keep attempting tool calls, so **removing the tools entirely** is the critical technique. A `SystemMessage` is also injected to nudge the LLM to summarize rather than request more tools.

## Parallel Tool Execution

Tool execution is handled by `ToolCallOrchestrator`, a dedicated class extracted from the main executor.

### Execution Pattern

```kotlin
// ToolCallOrchestrator.executeInParallel()
suspend fun executeInParallel(
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
private suspend fun executeSingleToolCall(...): ParallelToolExecution {
    // 1. Check intent-based tool allowlist
    if (allowedTools != null && toolName !in allowedTools) return error response

    // 2. BeforeToolCall Hook
    hookExecutor?.executeBeforeToolCall(toolCallContext)
    // → On Reject, only that specific tool is skipped

    // 3. Human-in-the-Loop approval check (ToolApprovalPolicy)
    checkToolApproval(toolName, toolCallContext, hookContext)

    // 4. Verify tool exists before reserving a slot
    val toolExists = findToolAdapter(toolName, tools) != null
        || springCallbacksByName.containsKey(toolName)

    // 5. Reserve maxToolCalls slot with CAS (concurrency-safe)
    reserveToolExecutionSlot(totalToolCallsCounter, maxToolCalls)
    // → Uses compareAndSet loop instead of getAndIncrement

    // 6. Execute tool (with per-tool timeout)
    val invocation = invokeToolAdapter(toolName, toolInput, tools, ...)
    // → toolsUsed.add(toolName) only after confirming adapter exists

    // 7. Sanitize tool output (ToolOutputSanitizer, for indirect injection defense)

    // 8. AfterToolCall Hook
    hookExecutor?.executeAfterToolCall(context, result)

    // 9. Record metrics
    agentMetrics.recordToolCall(toolName, durationMs, success)
}
```

**Note:** `toolsUsed.add(toolName)` is only called after confirming that the adapter exists. This is because the LLM may generate (hallucinate) tool names that do not exist.

## Context Window Management

Context trimming is handled by `ConversationMessageTrimmer`.

### Token Budget Calculation

```
budget = maxContextWindowTokens - systemPromptTokens - maxOutputTokens - toolTokenReserve
```

`toolTokenReserve` accounts for the estimated tokens needed to describe active tool definitions (~200 tokens per tool).

Example: `128000 - 2000 - 4096 - 2000 = 119904 tokens`

### 3-Phase Trimming

```kotlin
// ConversationMessageTrimmer.trim()
fun trim(messages: MutableList<Message>, systemPrompt: String, toolTokenReserve: Int = 0)
```

**Phase 1: Remove old history (from the front)**

```
[SystemMessages...] [History messages...] [Current UserMessage] [Tool interactions...]
                     ↑ Removal starts here
```

Removes messages starting from the oldest non-SystemMessage. Stops when it reaches the **last UserMessage (current prompt)**. Leading `SystemMessage` entries (e.g., hierarchical memory facts) are preserved during this phase.

**Phase 1.5: Drop leading SystemMessages to preserve fresh tool context**

If Phase 1 is insufficient and there are tool interactions after the UserMessage, leading memory `SystemMessage` entries are dropped to keep the most recent tool-call/tool-response context visible to the LLM.

**Phase 2: Remove tool interactions (after UserMessage)**

If still over budget, removes tool call/response pairs that come after the current UserMessage.

### Message Pair Integrity

```kotlin
private fun calculateRemoveGroupSize(messages: List<Message>): Int {
    val first = messages[0]
    // Remove AssistantMessage(toolCalls) → ToolResponseMessage as a pair
    if (first is AssistantMessage && !first.toolCalls.isNullOrEmpty()) {
        return if (messages.size > 1 && messages[1] is ToolResponseMessage) 2 else 1
    }
    return 1
}
```

`AssistantMessage(toolCalls)` and `ToolResponseMessage` must always be removed as a pair. If only one remains, it will cause an LLM API error.

### Token Estimation

```kotlin
private fun estimateMessageTokens(message: Message): Int {
    val contentTokens = when (message) {
        is UserMessage -> tokenEstimator.estimate(message.text)
        is AssistantMessage -> {
            val textTokens = tokenEstimator.estimate(message.text ?: "")
            val toolCallTokens = message.toolCalls.sumOf {
                tokenEstimator.estimate(it.name() + it.arguments())
            }
            textTokens + toolCallTokens
        }
        is SystemMessage -> tokenEstimator.estimate(message.text)
        is ToolResponseMessage -> message.responses.sumOf {
            tokenEstimator.estimate(it.responseData())
        }
        // ...
    }
    return contentTokens + MESSAGE_STRUCTURE_OVERHEAD  // +20 tokens per message
}
```

Each message estimate includes a fixed `MESSAGE_STRUCTURE_OVERHEAD` (20 tokens) for role tags, separators, and metadata.

`TokenEstimator` is CJK-aware and uses a Caffeine cache (50,000 entries, 5-minute TTL). Strings longer than 2,000 characters use SHA-256 hash keys instead of the raw string, to avoid retaining large heap objects while still benefiting from caching:
- Latin characters: ~4 chars/token
- Korean/CJK: ~1.5 chars/token
- Emoji: ~1 char/token

## LLM Retry (RetryExecutor)

Retry logic is extracted into `RetryExecutor`, which optionally wraps a `CircuitBreaker`.

### Exponential Backoff + Jitter

```kotlin
// RetryExecutor.execute()
suspend fun <T> execute(block: suspend () -> T): T {
    val retryBlock: suspend () -> T = {
        repeat(maxAttempts) { attempt ->
            try {
                return@repeat block()
            } catch (e: Exception) {
                e.throwIfCancellation()  // Never retry cancellation
                if (!isTransientError(e) || attempt == maxAttempts - 1) throw e

                // Exponential backoff with ±25% jitter
                val baseDelay = min(initialDelay * multiplier^attempt, maxDelay)
                val jitter = baseDelay * 0.25 * random(-1, 1)
                delay(baseDelay + jitter)
            }
        }
    }
    // Optionally wrapped with CircuitBreaker
    return circuitBreaker?.execute(retryBlock) ?: retryBlock()
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
- `CircuitBreakerOpenException` (circuit is open — fail fast)

## Concurrency Semaphore

```kotlin
concurrencySemaphore.withPermit {
    executeWithRequestTimeout(properties.concurrency.requestTimeoutMs) {
        agentExecutionCoordinator.execute(command, hookContext, toolsUsed, startTime)
    }
}
```

`Semaphore(permits = maxConcurrentRequests)` limits the number of concurrent agent executions. When the limit is exceeded, coroutines will suspend and wait.

## Error Classification

```kotlin
// AgentErrorPolicy.classify()
fun classify(e: Exception): AgentErrorCode {
    return when {
        e is CircuitBreakerOpenException → CIRCUIT_BREAKER_OPEN
        "rate limit" in message          → RATE_LIMITED
        "timeout" in message             → TIMEOUT
        "context length" in msg          → CONTEXT_TOO_LONG
        "tool" in message                → TOOL_ERROR
        else                             → UNKNOWN
    }
}
```

Classification is based on error messages, which are then converted to user-friendly messages through `ErrorMessageResolver`.

## System Prompt Construction

System prompt assembly is handled by `SystemPromptBuilder`:

```kotlin
// SystemPromptBuilder.build()
fun build(
    basePrompt: String,
    ragContext: String?,
    responseFormat: ResponseFormat = ResponseFormat.TEXT,
    responseSchema: String? = null,
    userPrompt: String? = null,
    workspaceToolAlreadyCalled: Boolean = false
): String {
    val parts = mutableListOf(basePrompt)
    parts.add(buildGroundingInstruction(...))  // Grounding rules

    if (ragContext != null) {
        parts.add(buildRagInstruction(ragContext))
    }

    when (responseFormat) {
        ResponseFormat.JSON -> parts.add(buildJsonInstruction(responseSchema))
        ResponseFormat.YAML -> parts.add(buildYamlInstruction(responseSchema))
        ResponseFormat.TEXT -> {}
    }

    val result = parts.joinToString("\n\n")
    return postProcessor?.process(result) ?: result  // Canary token injection
}
```

Final system prompt structure:

```
{User-defined system prompt}

[Grounding Rules]
{Fact-grounding and tool-call directives}

[Retrieved Context]
{Documents retrieved via RAG}

[Response Format]
You MUST respond with valid JSON only.
Expected JSON schema: {...}
```

The builder also supports YAML response format and an optional `SystemPromptPostProcessor` for canary token injection.

## Tool Selection and Preparation

Tool selection is handled by `ToolPreparationPlanner`:

```kotlin
// ToolPreparationPlanner.prepareForPrompt()
fun prepareForPrompt(userPrompt: String): List<Any> {
    // 1. LocalTool (@Tool annotation-based), filtered through LocalToolFilters
    val localToolInstances = localToolFilters.fold(localTools.toList()) { acc, filter ->
        filter.filter(acc)
    }

    // 2. Collect ToolCallback + MCP tools, then deduplicate by tool name
    val allCallbacks = deduplicateCallbacks(toolCallbacks + mcpToolCallbacks())

    // 3. Filter via ToolSelector (based on user prompt)
    val selectedCallbacks = toolSelector?.select(userPrompt, allCallbacks)
        ?: allCallbacks

    // 4. Wrap with ArcToolCallbackAdapter (WeakHashMap cache)
    val wrappedCallbacks = selectedCallbacks.map(::resolveAdapter)

    // 5. Apply maxToolsPerRequest
    return (localToolInstances + wrappedCallbacks).take(maxToolsPerRequest)
}
```

Duplicate callback names are resolved before selection and wrapping.
The first callback in the merged callback list is kept, and later duplicates are dropped with a warning log. Adapters are cached in a `WeakHashMap` to avoid creating new wrappers on every request.

## ArcToolCallbackAdapter

An adapter that bridges Spring AI's `ToolCallback` and Arc Reactor's `ToolCallback`:

```kotlin
internal class ArcToolCallbackAdapter(
    val arcCallback: ToolCallback,
    fallbackToolTimeoutMs: Long = 15_000
) : org.springframework.ai.tool.ToolCallback {

    private val blockingInvoker = BlockingToolCallbackInvoker(fallbackToolTimeoutMs)

    override fun call(toolInput: String): String {
        val parsedArguments = parseToolArguments(toolInput)
        // suspend fun → blocking conversion (Spring AI interface constraint)
        return blockingInvoker.invokeWithTimeout(arcCallback, parsedArguments)
    }
}
```

`BlockingToolCallbackInvoker` uses `runBlocking(Dispatchers.IO)` with `withTimeout` because the Spring AI interface only supports a synchronous `call()`. Per-tool timeouts are respected via `toolCallback.timeoutMs`, falling back to the configured `toolCallTimeoutMs`.

## CancellationException Rule

In every `suspend fun`, `CancellationException` must be caught and rethrown before catching a generic `Exception`. The codebase uses a `throwIfCancellation()` extension to enforce this:

```kotlin
try {
    // Business logic
} catch (e: Exception) {
    e.throwIfCancellation()  // Rethrows if CancellationException
    // Error handling
}
```

This pattern applies to **all** suspend functions including `execute()`, `executeWithTools()`, `RetryExecutor.execute()`, and others. If omitted, `withTimeout` will not function correctly.

> `java.util.concurrent.CancellationException` is a typealias for `kotlin.coroutines.cancellation.CancellationException`.
