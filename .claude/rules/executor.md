---
paths:
  - "src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt"
---

# SpringAiAgentExecutor Rules

This is the most complex file in the project (~1,060 lines). Always follow these rules when modifying.

## CancellationException Handling

Always catch and rethrow CancellationException before any generic Exception catch in all suspend funs:

```kotlin
try {
    // ...
} catch (e: CancellationException) {
    throw e  // Preserve structured concurrency
} catch (e: Exception) {
    // Error handling
}
```

`java.util.concurrent.CancellationException` is a typealias for `kotlin.coroutines.cancellation.CancellationException`.

## ReAct Loop

- On maxToolCalls reached, always set `activeTools = emptyList()` — removing tools forces LLM to generate a final answer
- Tool execution uses `coroutineScope { map { async { } }.awaitAll() }` pattern (parallel)
- Only add tool name to `toolsUsed` after confirming adapter exists (prevents LLM hallucination)

## Context Window Management

- Never remove the last UserMessage (current prompt) during trimming
- AssistantMessage(toolCalls) + ToolResponseMessage must be removed as a pair
- Phase 2 guard: use `>` (not `>=`)

## Error Handling

- All `AgentResult.failure()` calls must include appropriate `errorCode`
- Use `classifyError(e)` → `AgentErrorCode` mapping
- Wrap hook/memory calls in catch/finally blocks with try-catch (prevents masking the original error)
- Streaming memory save runs in `finally` block outside `withTimeout`
- Skip `saveConversationHistory` on failure (`!result.success`)

## Streaming

- Only save `lastIterationContent` (not accumulated content)
- callWithRetry only wraps Flux creation, not consumption (known limitation)
