# Arc Reactor Example Guides

Domain-specific, end-to-end examples showing how to build real-world applications with Arc Reactor. Each guide contains working Kotlin code grounded in the actual framework APIs.

## Examples

| Guide | Pattern | Key Features |
|-------|---------|-------------|
| [Customer Support Bot](customer-support-bot.md) | Single agent + tools | ToolCallback, Guard, HITL approval |
| [Code Review Assistant](code-review-assistant.md) | Parallel multi-agent | Parallel orchestration, JSON output |
| [RAG Knowledge Base](rag-knowledge-base.md) | RAG pipeline | Document ingestion, vector search, query transformation |
| [Multi-Agent Pipeline](multi-agent-pipeline.md) | Sequential multi-agent | Supervisor pattern, WorkerAgentTool |

## Before You Start

All examples assume:

- Arc Reactor is forked and running (`./gradlew :arc-app:bootRun`)
- An LLM API key is set (`GEMINI_API_KEY`, `SPRING_AI_OPENAI_API_KEY`, or `SPRING_AI_ANTHROPIC_API_KEY`)
- You are familiar with the [Getting Started guide](../getting-started/example-app.md)

## Core Concepts Across All Examples

### ToolCallback

Every custom tool implements one interface:

```kotlin
interface ToolCallback {
    val name: String           // LLM calls the tool by this name
    val description: String    // LLM reads this to decide when to call the tool
    val inputSchema: String    // JSON Schema describing the tool parameters
    suspend fun call(arguments: Map<String, Any?>): Any?
}
```

Return errors as strings (`"Error: reason"`). Never throw exceptions from `call()`.

### AgentCommand

The input to every agent execution:

```kotlin
AgentCommand(
    systemPrompt = "...",          // Agent persona and instructions
    userPrompt = "...",            // User input
    userId = "user-123",           // Required for Guard pipeline
    maxToolCalls = 10,             // ReAct loop iteration limit
    responseFormat = ResponseFormat.TEXT,  // TEXT, JSON, or YAML
    metadata = mapOf("sessionId" to "session-abc")  // Enables conversation memory
)
```

### AgentResult

Every `agentExecutor.execute()` call returns:

```kotlin
data class AgentResult(
    val success: Boolean,
    val content: String?,          // Response text on success
    val errorCode: AgentErrorCode?,
    val errorMessage: String?,
    val toolsUsed: List<String>,
    val tokenUsage: TokenUsage?,
    val durationMs: Long
)
```

## Related Documentation

- [Implementation Guide](../architecture/implementation-guide.md) — ToolCallback, Guard, Hook templates
- [Guard & Hook System](../architecture/guard-hook.md) — Security pipeline internals
- [Multi-Agent Guide](../architecture/multi-agent.md) — Supervisor, Sequential, Parallel patterns
- [ReAct Loop](../architecture/react-loop.md) — How the agent reasoning loop works
- [Memory & RAG](../architecture/memory-rag/deep-dive.md) — Conversation memory and retrieval
