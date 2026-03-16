# Architecture Guide

## Overall Structure

Arc Reactor runtime is organized around 6 core components:

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

For Gradle module boundaries and runtime assembly entrypoints, see
[Module Layout Guide](module-layout.md).

## Request Processing Flow

The full request lifecycle is managed by `SpringAiAgentExecutor` and its
internal coordinators. Each stage records latency metrics and is individually
traceable via OpenTelemetry spans.

```
Request
  │
  ├─ 1. Concurrency Gate       (semaphore, request timeout)
  ├─ 2. Guard Pipeline         (input security checks)
  ├─ 3. Hook: BeforeAgentStart (authentication, billing, can reject)
  ├─ 4. Intent Classification  (optional, route to intent profile)
  ├─ 5. Response Cache Check   (optional, exact + semantic lookup)
  ├─ 6. Load Conversation History (with optional summary injection)
  ├─ 7. RAG Retrieval          (optional, with adaptive routing)
  ├─ 8. Tool Selection         (ToolSelector filters available tools)
  ├─ 9. ReAct Loop             (LLM <-> Tool, with before/after hooks)
  ├─10. Fallback Strategy      (optional, on ReAct failure)
  ├─11. Output Guard Pipeline  (PII masking, leakage detection, dynamic rules)
  ├─12. Output Boundary Check  (min/max length, retry for longer response)
  ├─13. Response Filtering     (ResponseFilterChain: max length, verified sources)
  ├─14. Citation Formatting    (optional, append verified source links)
  ├─15. Save Conversation History (async summarization when enabled)
  ├─16. Hook: AfterAgentComplete (billing, statistics, logging)
  │
  └─ Response
```

### 1. Guard Pipeline (Security Checks)

**Input Guard** (before execution):
```
Request → UnicodeNorm → RateLimit → InputValidation → InjectionDetection → Classification → Pass
          (order=0)     (order=1)    (order=2)          (order=3)            (opt-in, order=4)
```

Additional opt-in input guard stages:
- **TopicDriftDetection** (order=10) — detects off-topic requests based on conversation history
- **Permission** (order=5) — custom permission checks

**Output Guard** (after execution, opt-in via `arc.reactor.output-guard.enabled=true`):
```
Response → SystemPromptLeakage → PiiMasking → DynamicRule → RegexPattern → Pass
           (opt-in, order=5)     (order=10)    (order=15)    (order=20)
```

- 5-layer defense architecture aligned with OWASP LLM Top 10 (2025)
- Each stage implements `GuardStage` (input) or `OutputGuardStage` (output)
- Execution order is determined by the `order` field (lower values execute first)
- If any stage returns `Rejected`, processing stops immediately
- Register as a `@Component` bean and it is automatically added to the pipeline
- **Input guard** is fail-close and enabled by default (`guard.enabled=true`)
- **Output guard** is fail-close and opt-in (`output-guard.enabled=true`)
- **Canary tokens** enable system prompt leakage detection (`guard.canary-token-enabled=true`).
  When enabled, a `SystemPromptPostProcessor` injects canary tokens into system prompts,
  and `SystemPromptLeakageOutputGuard` checks responses for leaked tokens
- **Tool output sanitization** defends against indirect prompt injection
  (`guard.tool-output-sanitization-enabled=true`)
- See [Guard & Hook Guide](guard-hook.md) for full layer details and OWASP coverage

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

### 3. Intent Classification (Optional)

When `arc.reactor.intent.enabled=true`, intent classification runs after the
guard pipeline and before the cache check:

```
UserPrompt → RuleBasedClassifier → (threshold not met?) → LlmClassifier → IntentProfile
```

- Composite classifier: rule-based first, cascades to LLM if confidence is below threshold
- Blocked intents (configured via `intent.blockedIntents`) immediately reject with `GUARD_REJECTED`
- The resolved intent profile can override system prompt, allowed tools, temperature, and max tool calls
- Fail-safe: on classification errors the original command is used unchanged

### 4. Response Cache (Optional)

When `arc.reactor.cache.enabled=true`, responses are cached to avoid redundant LLM calls:

- **Exact cache**: key built from user prompt + system prompt + tool names + temperature
- **Semantic cache** (opt-in via `cache.semantic.enabled=true`): Redis + embedding similarity
- Only cacheable when effective temperature is at or below `cache.cacheableTemperature`
- Cache hits skip the entire ReAct loop and return immediately

### 5. ReAct Loop (Core Execution Loop)

```
while (true) {
    1. Context window trimming (fit within token budget)
    2. LLM call (with retry + circuit breaker)
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
- Optional circuit breaker (`circuit-breaker.enabled=true`) opens after repeated failures

**Parallel Tool Execution:**
- `coroutineScope { map { async { } }.awaitAll() }`
- Order is preserved (by map index)
- BeforeToolCall/AfterToolCall hooks are executed for each tool
- Tool approval policy (HITL) can pause execution for human review
- Tool output sanitization strips injection attempts from tool results

**Fallback Strategy:**
- When `fallback.enabled=true` and the ReAct loop fails, the system retries
  with alternative LLM models configured in `fallback.models`
- Metadata records whether a fallback was used (`fallbackUsed=true`)

### 6. Post-Execution Pipeline

After the ReAct loop produces a result, the `ExecutionResultFinalizer` runs
these stages in order:

1. **Output Guard** — PII masking, system prompt leakage detection, dynamic
   rules, regex patterns. Fail-close: rejection returns `OUTPUT_GUARD_REJECTED`
2. **Output Boundary Check** — enforces `boundaries.outputMinChars` and
   `boundaries.outputMaxChars`. When the response is too short and no tools
   were used, an additional LLM call attempts a longer response. If the
   response is still too short, returns `OUTPUT_TOO_SHORT`
3. **Re-guard** — if the boundary check changed the content (e.g., longer
   response retry produced new output), the output guard runs again
4. **Response Filter Chain** — pluggable filters applied in order:
   `MaxLengthResponseFilter` (when `response.maxLength > 0`),
   `VerifiedSourcesResponseFilter` (always registered)
5. **Citation Formatting** — when `citation.enabled=true`, appends a
   numbered source list from `hookContext.verifiedSources`
6. **Save Conversation History** — calls `conversationManager.saveHistory()`
   on success. When memory summarization is enabled, summaries are generated
   asynchronously
7. **Hook: AfterAgentComplete** — fires the after-completion hook with the
   final result, tools used, and total duration

### 7. Memory System

```
ConversationManager (conversation lifecycle management)
         │
         ├── MemoryStore (message storage)
         │   ├── InMemoryMemoryStore    ← Default (lost on server restart)
         │   └── JdbcMemoryStore        ← PostgreSQL (@Primary when DataSource detected)
         │
         ├── ConversationSummaryService (optional, LLM-based summarization)
         │   └── LlmConversationSummaryService
         │
         └── ConversationSummaryStore (optional, summary persistence)
             ├── InMemoryConversationSummaryStore
             └── JdbcConversationSummaryStore  ← PostgreSQL (@Primary)
```

- `ConversationManager.loadHistory()`: loads session history, injects summaries when available
- `ConversationManager.saveHistory()`: saves only on success
- `maxConversationTurns` limits history size
- **Hierarchical memory** (opt-in via `memory.summary.enabled=true`): when conversation
  history exceeds the turn limit, an LLM-based service generates a narrative summary
  that is stored separately and injected into future conversations
- **User memory** (opt-in via `memory.user.enabled=true`): per-user long-term memory
  that persists across sessions. When `memory.user.inject-into-prompt=true`, user
  context is appended to the system prompt via `UserMemoryInjectionHook`

### 8. RAG Pipeline

```
Query → QueryTransformer → QueryRouter → DocumentRetriever → DocumentReranker
         (passthrough/     (adaptive      (vector search)     (score-based
          hyde/decompose)   routing)                            re-ranking)
                                              │
                                              ▼
                              ContextCompressor → Context Builder → Injected into System Prompt
                              (optional, LLM)     (token-aware)
```

- Activate with `arc.reactor.rag.enabled=true`
- Requires a `VectorStore` bean (e.g., PGVector)
- **Query transformation** (`rag.queryTransformer`): `passthrough` (default), `hyde`
  (hypothetical document embedding), or `decomposition` (multi-sub-query)
- **Adaptive routing** (opt-in via `rag.adaptive-routing.enabled=true`): classifies
  query complexity (SIMPLE vs COMPLEX) via LLM and adjusts `topK` accordingly
- **Hybrid search** (opt-in via `rag.hybrid.enabled=true`): combines BM25 keyword
  scoring with vector similarity using Reciprocal Rank Fusion (RRF)
- **Context compression** (opt-in via `rag.compression.enabled=true`): LLM-based
  compression removes irrelevant content before injection
- **Parent document retrieval** (opt-in via `rag.parent-retrieval.enabled=true`):
  expands retrieved chunks to include surrounding context

### 9. MCP Integration

```
McpManager
├── register(server)    → Configure STDIO or SSE transport
├── connect(name)       → Connect to server + retrieve tool list
└── getAllToolCallbacks() → Return MCP tools deduplicated by tool name
```

- Tools from external MCP servers are automatically added to the agent's tool list
- Both STDIO (local process) and SSE (remote server) are supported
- MCP security policy controls allowed server names and max tool output length

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
| `GUARD_REJECTED` | Guard pipeline rejection | Rate limit, input validation, blocked intent, etc. |
| `HOOK_REJECTED` | Before hook rejection | Authentication, cost overrun, etc. |
| `RATE_LIMITED` | Guard rate limit or LLM API 429 | Rate limit exceeded |
| `TIMEOUT` | Request timeout | withTimeout expiration |
| `CONTEXT_TOO_LONG` | Context exceeded | LLM input limit exceeded |
| `TOOL_ERROR` | Tool execution failure | Internal tool error |
| `INVALID_RESPONSE` | Structured response validation | LLM returned invalid JSON/format |
| `OUTPUT_GUARD_REJECTED` | Output guard pipeline rejection | PII leak, prompt leakage, policy violation |
| `OUTPUT_TOO_SHORT` | Output boundary enforcement | Response below minimum character threshold |
| `CIRCUIT_BREAKER_OPEN` | Circuit breaker tripped | Service unavailable due to repeated failures |
| `UNKNOWN` | Other errors | Unclassified |

Implement `ErrorMessageResolver` to customize error messages (e.g., localization to other languages).

## Spring Auto-Configuration

`ArcReactorAutoConfiguration` automatically registers all beans. The auto-configuration
is split into focused `@Import` classes:

| Configuration Class | Scope |
|---------------------|-------|
| `TracingConfiguration` | OTel tracer (or NoOp fallback) |
| `ArcReactorCoreBeansConfiguration` | Core beans: stores, metrics, tools, policies |
| `ArcReactorHookAndMcpConfiguration` | HookExecutor, McpManager, webhook hooks |
| `ArcReactorRuntimeConfiguration` | ChatClient, ChatModelProvider, ResponseFilterChain, ResponseCache, FallbackStrategy, CircuitBreakerRegistry |
| `ArcReactorExecutorConfiguration` | AgentExecutor (wires all dependencies) |
| `GuardConfiguration` | Input guard stages |
| `OutputGuardConfiguration` | Output guard stages + pipeline |
| `RagConfiguration` | RAG pipeline, retrievers, query router, chunker |
| `IntentConfiguration` | Intent classifier + resolver |
| `MemorySummaryConfiguration` | Conversation summary service + store |
| `UserMemoryConfiguration` | Per-user long-term memory |
| `CanaryConfiguration` | Canary token injection + leakage detection |
| `ToolSanitizerConfiguration` | Tool output sanitization |
| `PromptCachingConfiguration` | Anthropic prompt caching |
| `HealthIndicatorConfiguration` | LLM, database, and MCP health indicators |
| `AuthConfiguration` | JWT authentication |
| `SchedulerConfiguration` | Scheduled job execution |
| `ArcReactorSemanticCacheConfiguration` | Redis semantic response cache |

Key auto-configured beans:

| Bean | Condition | Default |
|------|-----------|---------|
| `MemoryStore` | `DataSource` present → JDBC, absent → InMemory | InMemory |
| `ConversationManager` | `@ConditionalOnMissingBean` | DefaultConversationManager |
| `ToolSelector` | Strategy: `all`, `keyword`, or `semantic` | AllToolSelector |
| `ErrorMessageResolver` | `@ConditionalOnMissingBean` | English messages |
| `AgentMetrics` | `MeterRegistry` present → Micrometer, absent → NoOp | NoOpAgentMetrics |
| `TokenEstimator` | `@ConditionalOnMissingBean` | DefaultTokenEstimator |
| `HookExecutor` | Collects registered Hook beans | Empty Hook list |
| `RequestGuard` | `guard.enabled=true` (default) | UnicodeNorm + RateLimit + InputValidation + InjectionDetection |
| `OutputGuardPipeline` | `output-guard.enabled=true` (opt-in) | PiiMasking + DynamicRule + RegexPattern |
| `ChatModelProvider` | `@ConditionalOnMissingBean` | Multi-model provider from all ChatModel beans |
| `ResponseFilterChain` | `response.filtersEnabled=true` | VerifiedSourcesResponseFilter (+ MaxLength when configured) |
| `ResponseCache` | `cache.enabled=true` (opt-in) | CaffeineResponseCache |
| `FallbackStrategy` | `fallback.enabled=true` (opt-in) | ModelFallbackStrategy |
| `CircuitBreakerRegistry` | `circuit-breaker.enabled=true` (opt-in) | Configurable thresholds |
| `IntentResolver` | `intent.enabled=true` (opt-in) | Composite (rule + LLM) classifier |
| `RagPipeline` | `rag.enabled=true` (opt-in) | DefaultRagPipeline (or HybridRagPipeline) |
| `QueryRouter` | `rag.adaptive-routing.enabled=true` (opt-in) | AdaptiveQueryRouter |
| `ArcReactorTracer` | OTel on classpath + `tracing.enabled=true` (default) | OtelArcReactorTracer (or NoOp) |
| `ToolApprovalPolicy` | `approval.enabled=true` (opt-in) | DynamicToolApprovalPolicy |
| `ConversationSummaryService` | `memory.summary.enabled=true` (opt-in) | LlmConversationSummaryService |
| `UserMemoryManager` | `memory.user.enabled=true` (opt-in) | UserMemoryManager + InjectionHook |

All beans are declared with `@ConditionalOnMissingBean`, so registering your own bean will override the auto-configured default.
