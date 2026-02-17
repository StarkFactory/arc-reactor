# Architecture Guide

## Overall Structure

Arc Reactor consists of 6 core modules:

```
                        ┌─────────────┐
                        │  Controller │  REST API (chat, streaming)
                        └──────┬──────┘
                               │
                        ┌──────▼──────┐
                        │   Agent     │  ReAct loop + parallel tool execution
                        │  Executor   │  Context window management + LLM retry
                        └──┬───┬───┬──┘
                           │   │   │
              ┌────────────┘   │   └────────────┐
              ▼                ▼                 ▼
        ┌──────────┐   ┌──────────┐      ┌──────────┐
        │  Guard   │   │   Hook   │      │   Tool   │
        │ Pipeline │   │ Executor │      │  System  │
        └──────────┘   └──────────┘      └──────────┘
                               │
                    ┌──────────┼──────────┐
                    ▼          ▼          ▼
              ┌──────────┐ ┌──────┐ ┌─────────┐
              │  Memory  │ │ RAG  │ │   MCP   │
              └──────────┘ └──────┘ └─────────┘
```

## Request Processing Flow

### 1. Guard Pipeline (Security Checks)

```
Request → RateLimit → InputValidation → InjectionDetection → Classification → Permission → Pass
          (10/min)    (10,000 char limit) (prompt injection)    (categorization)  (authorization)
```

- Each stage implements the `GuardStage` interface
- Execution order is determined by the `order` field (lower values execute first)
- If any stage returns `Rejected`, processing stops immediately
- Register as a `@Component` bean and it is automatically added to the pipeline

### 2. Hook System (Lifecycle)

```
BeforeAgentStart → [Agent Execution] → AfterAgentComplete
                         │
                  BeforeToolCall → [Tool Execution] → AfterToolCall
```

| Hook | Timing | Purpose |
|------|--------|---------|
| `BeforeAgentStart` | Before agent starts | Authentication, billing checks, can reject |
| `BeforeToolCall` | Before tool invocation | Audit logging, tool blocking |
| `AfterToolCall` | After tool invocation | Result recording, notifications |
| `AfterAgentComplete` | After agent completes | Billing, statistics, logging |

- Return `HookResult.Reject` to abort execution
- Return `HookResult.Continue` to proceed
- Hook exceptions do not affect the agent result (they are isolated)

### 3. ReAct Loop (Core Execution Loop)

```
while (true) {
    1. Context window trimming (fit within token budget)
    2. LLM call (with retry)
    3. Tool call detected?
       - No  → Return final response
       - Yes → Execute tools in parallel → Append results to messages → Continue loop
    4. When maxToolCalls is reached, remove tools → Request final answer
}
```

**Context Window Trimming:**
- Budget = maxContextWindowTokens - systemPromptTokens - maxOutputTokens
- When exceeded, the oldest messages are removed first
- AssistantMessage(toolCalls) + ToolResponseMessage are removed as a pair
- The current user prompt (last UserMessage) is never removed

**LLM Retry:**
- Transient errors (429, 5xx, timeout) → Exponential backoff with +/-25% jitter
- Non-transient errors (authentication, context exceeded) → Fail immediately
- `CancellationException` is never retried (respects structured concurrency)

**Parallel Tool Execution:**
- `coroutineScope { map { async { } }.awaitAll() }`
- Order is preserved (by map index)
- BeforeToolCall/AfterToolCall hooks are executed for each tool

### 4. Memory System

```
ConversationManager (conversation lifecycle management)
         │
         ▼
    MemoryStore (storage)
    ├── InMemoryMemoryStore    ← Default (lost on server restart)
    └── JdbcMemoryStore        ← PostgreSQL (auto-switches when DataSource is detected)
```

- `ConversationManager.loadHistory()`: Loads session history
- `ConversationManager.saveHistory()`: Saves only on success
- `maxConversationTurns` limits history size

### 5. RAG Pipeline

```
Query → DocumentRetriever → DocumentReranker → Context Builder → Injected into System Prompt
         (vector search)     (re-ranking)       (token-aware build)
```

- Activate with `arc.reactor.rag.enabled = true`
- If a `VectorStore` bean exists, Spring AI VectorStore is used
- Otherwise, InMemoryDocumentRetriever is used (for testing)

### 6. MCP Integration

```
McpManager
├── register(server)    → Configure STDIO or SSE transport
├── connect(name)       → Connect to server + retrieve tool list
└── getAllToolCallbacks() → Return all connected MCP tools
```

- Tools from external MCP servers are automatically added to the agent's tool list
- Both STDIO (local process) and SSE (remote server) are supported

## Multi-Agent Architecture

```
MultiAgent (DSL Builder)
├── .sequential() → SequentialOrchestrator
│                    A → B → C (output becomes next input)
├── .parallel()   → ParallelOrchestrator
│                    A ┐
│                    B ├→ ResultMerger → Combined result
│                    C ┘
└── .supervisor()  → SupervisorOrchestrator
                     Supervisor ← WorkerAgentTool(A)
                              ← WorkerAgentTool(B)
                              ← WorkerAgentTool(C)
```

**Supervisor key concept:** `WorkerAgentTool` wraps an agent as a `ToolCallback`, allowing the existing ReAct loop to invoke workers "as if they were tools." This works without any modifications to `SpringAiAgentExecutor`.

## Error Handling

All failures have an `errorCode` and `errorMessage` set in the `AgentResult`:

| errorCode | When it occurs | Description |
|-----------|----------------|-------------|
| `GUARD_REJECTED` | Guard pipeline rejection | Rate limit, input validation, etc. |
| `HOOK_REJECTED` | Before hook rejection | Authentication, cost overrun, etc. |
| `RATE_LIMITED` | LLM API 429 | Rate limit exceeded |
| `TIMEOUT` | Request timeout | withTimeout expiration |
| `CONTEXT_TOO_LONG` | Context exceeded | LLM input limit exceeded |
| `TOOL_ERROR` | Tool execution failure | Internal tool error |
| `UNKNOWN` | Other errors | Unclassified |

Implement `ErrorMessageResolver` to customize error messages (e.g., localization to other languages).

## Spring Auto-Configuration

`ArcReactorAutoConfiguration` automatically registers all beans:

| Bean | Condition | Default |
|------|-----------|---------|
| `MemoryStore` | `DataSource` present → JDBC, absent → InMemory | InMemory |
| `ConversationManager` | `@ConditionalOnMissingBean` | DefaultConversationManager |
| `ToolSelector` | `@ConditionalOnMissingBean` | AllToolSelector |
| `ErrorMessageResolver` | `@ConditionalOnMissingBean` | English messages |
| `AgentMetrics` | `@ConditionalOnMissingBean` | NoOpAgentMetrics |
| `TokenEstimator` | `@ConditionalOnMissingBean` | DefaultTokenEstimator |
| `HookExecutor` | Collects registered Hook beans | Empty Hook list |
| `RequestGuard` | `guard.enabled = true` | RateLimit + InputValidation + InjectionDetection |

All beans are declared with `@ConditionalOnMissingBean`, so registering your own bean will override the auto-configured default.
