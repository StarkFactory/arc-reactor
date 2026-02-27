# Guard Pipeline & Hook System

> This document explains the internal workings of Arc Reactor's security layer (Guard) and lifecycle extension points (Hook).

## Guard System

### Overview

Guard is a multi-layer security system covering both **input** (before execution) and **output** (after execution). The architecture is organized into 5 defense layers aligned with the OWASP LLM Top 10 (2025).

```
Request
  │
  ▼
[Input Guard Pipeline] ── 6 ordered stages, fail-close
  │  L0: UnicodeNormalization → RateLimit → InputValidation → InjectionDetection
  │  L1: Classification → TopicDriftDetection (opt-in)
  │
  ▼
[Agent Execution] ── ReAct loop with tool calls
  │  L3: ToolOutputSanitizer (opt-in, wraps tool outputs)
  │
  ▼
[Output Guard Pipeline] ── 4 ordered stages, fail-close
  │  L2: SystemPromptLeakageGuard (opt-in)
  │      PiiMaskingGuard → DynamicRuleGuard → RegexPatternGuard
  │
  ▼
Response
```

### Core Interfaces

**RequestGuard** (`guard/Guard.kt`):

```kotlin
interface RequestGuard {
    suspend fun guard(command: GuardCommand): GuardResult
}
```

**GuardStage** — Individual input check stage:

```kotlin
interface GuardStage {
    val stageName: String    // Stage name (for logging/error messages)
    val order: Int           // Execution order (lower runs first)
    val enabled: Boolean     // Can be disabled

    suspend fun check(command: GuardCommand): GuardResult
}
```

**OutputGuardStage** — Individual output check stage:

```kotlin
interface OutputGuardStage {
    val stageName: String
    val order: Int get() = 0
    val enabled: Boolean get() = true

    suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult
}
```

### GuardCommand and GuardResult

**Input:**

```kotlin
data class GuardCommand(
    val userId: String,                       // User ID
    val text: String,                         // Text to validate
    val channel: String? = null,              // Channel info (Slack, Web, etc.)
    val metadata: Map<String, Any> = emptyMap()
)
```

**Result (sealed class):**

```kotlin
sealed class GuardResult {
    data class Allowed(val hints: List<String> = emptyList())
    data class Rejected(
        val reason: String,                   // Rejection reason
        val category: RejectionCategory,      // Rejection category
        val stage: String? = null             // Which stage rejected the request
    )
}
```

**Output result (sealed class):**

```kotlin
sealed class OutputGuardResult {
    data class Allowed(...)
    data class Modified(val content: String, val reason: String, val stage: String?)
    data class Rejected(val reason: String, val stage: String?, val category: OutputRejectionCategory?)
}
```

**Rejection categories:**

| Category | Description |
|---------|------|
| `RATE_LIMITED` | Rate limit exceeded |
| `INVALID_INPUT` | Invalid input (empty string, length exceeded) |
| `PROMPT_INJECTION` | Prompt injection attack detected |
| `OFF_TOPIC` | Unrelated request |
| `UNAUTHORIZED` | Insufficient permissions |
| `SYSTEM_ERROR` | Error within the guard stage itself |

### 5-Layer Defense Architecture

#### Layer 0: Static Fast Filters (always ON, 0ms LLM cost)

##### Stage 0: UnicodeNormalization (order=0)

```kotlin
class UnicodeNormalizationStage : GuardStage
```

Defends against homoglyph and invisible-character attacks:

- **NFKC normalization**: fullwidth Latin → ASCII (e.g., `ｉｇｎｏｒｅ` → `ignore`)
- **Zero-width character stripping**: U+200B/C/D/E/F, U+FEFF, U+00AD, U+2060-2064, U+180E, Unicode Tag Block (U+E0000-E007F)
- **Homoglyph replacement**: Cyrillic а→a, е→e, о→o, р→p, с→c, etc. (15 common mappings)
- **Rejection**: If zero-width character ratio exceeds threshold (default 10%)
- Returns normalized text via `GuardResult.Allowed(hints = ["normalized:$text"])` — downstream stages see the cleaned text

##### Stage 1: RateLimit (order=1)

```kotlin
class DefaultRateLimitStage(
    private val requestsPerMinute: Int = 10,
    private val requestsPerHour: Int = 100,
    private val tenantRateLimits: Map<String, TenantRateLimit> = emptyMap()
) : RateLimitStage
```

- Tracks per-user request counts using a Caffeine cache
- Applies two windows: per-minute and per-hour
- **Tenant-aware**: Cache key is `"$tenantId:$userId"`, supports per-tenant overrides via `tenantRateLimits`
- Cache TTL automatically resets counters

##### Stage 2: InputValidation (order=2)

```kotlin
class DefaultInputValidationStage(
    private val maxLength: Int = 10000,
    private val minLength: Int = 1
) : InputValidationStage
```

- Rejects empty strings
- Rejects input exceeding maximum length (default 10,000 characters)

##### Stage 3: InjectionDetection (order=3)

```kotlin
class DefaultInjectionDetectionStage : InjectionDetectionStage
```

Detects prompt injection attacks using 28 regex patterns:

- **Role change attempts**: `"ignore previous"`, `"you are now"`, `"act as"`
- **System prompt extraction**: `"show me your prompt"`, `"repeat your instructions"`
- **Output manipulation**: `"output the following"`, `"print exactly"`
- **Encoding bypass**: `"base64"`, `"rot13"`, `"hex encode"`
- **Delimiter injection**: `###`, `---` (5+), `===` (5+), `<<<>>>`
- **ChatML / Llama tokens**: `<|im_start|>`, `<|im_end|>`, `[INST]`, `<start_of_turn>`
- **Authority escalation**: `"developer mode"`, `"system override"`
- **Safety override**: `"override safety filter"`, `"override content policy"`
- **Many-shot jailbreak**: 3+ sequential `example N` markers
- **Unicode escape sequences**: 4+ consecutive `\uXXXX` patterns

#### Layer 1: Classification (opt-in)

##### Stage 4: Classification (order=4)

```kotlin
class CompositeClassificationStage(
    ruleBasedStage: RuleBasedClassificationStage,
    llmStage: LlmClassificationStage? = null  // null when LLM classification disabled
) : ClassificationStage
```

Two-tier classification:
- **Rule-based** (always): Keyword matching against `blockedCategories` (e.g., malware, weapons, self_harm)
- **LLM-based** (opt-in): Uses `ChatClient` for semantic classification. Fail-open — LLM errors return `Allowed`

Enable: `arc.reactor.guard.classification-enabled=true`, `arc.reactor.guard.classification-llm-enabled=true`

##### Stage 6: TopicDriftDetection (order=6)

```kotlin
class TopicDriftDetectionStage : GuardStage
```

Defends against **Crescendo attacks** (multi-turn progressive jailbreaks):
- Reads `conversationHistory` from `GuardCommand.metadata`
- Scores escalating sensitivity patterns across a sliding window (last 5 turns)
- Pattern escalation: hypothetical → what if → for research → step by step → bypass/override
- Configurable `maxDriftScore` threshold (default 0.7)

#### Layer 2: System Prompt Protection (opt-in)

Prevents system prompt leakage via canary token injection:

```kotlin
class CanaryTokenProvider(seed: String)       // Generates deterministic CANARY-{8hex} token
class CanarySystemPromptPostProcessor         // Appends canary clause to system prompt
class SystemPromptLeakageOutputGuard          // Output guard stage (order=5)
```

- **CanaryTokenProvider** generates a SHA-256 deterministic token from a seed
- **CanarySystemPromptPostProcessor** appends `"The following token is secret..."` to the system prompt
- **SystemPromptLeakageOutputGuard** checks output for: (1) canary token presence, (2) leakage phrases (`"my system prompt is"`, `"I was instructed to"`)

Enable: `arc.reactor.guard.canary-token-enabled=true`

#### Layer 3: Tool Output Sanitization (opt-in)

Defends against **indirect prompt injection** via tool outputs:

```kotlin
class ToolOutputSanitizer {
    fun sanitize(toolName: String, output: String): SanitizedOutput
}
```

- Wraps tool output with data-instruction separation markers
- Strips injection patterns (role override, system delimiter, prompt override, data exfiltration attempts)
- Detected patterns → replaced with `[SANITIZED]`
- Integrates with `ToolCallOrchestrator` as an optional parameter

Enable: `arc.reactor.guard.tool-output-sanitization-enabled=true`

#### Layer 4: Audit & Observability (always ON when arc-admin enabled)

```kotlin
interface GuardAuditPublisher {
    fun publish(command: GuardCommand, stage: String, result: String,
                reason: String?, stageLatencyMs: Long, pipelineLatencyMs: Long)
}
```

- **GuardPipeline** records per-stage and pipeline-total latency, publishes via `GuardAuditPublisher`
- **OutputGuardPipeline** records per-stage actions via `onStageComplete` callback wired to `AgentMetrics`
- **MetricGuardAuditPublisher** (arc-admin): Publishes `GuardEvent` to `MetricRingBuffer` with SHA-256 input hash (never raw text)
- **Streaming support**: Output guard runs post-completion on collected content; emits `StreamEventMarker.error()` on rejection/modification

### Output Guard Pipeline

The output guard runs **after** agent execution completes, validating the response:

```kotlin
class OutputGuardPipeline(
    stages: List<OutputGuardStage>,
    private val onStageComplete: ((stage: String, action: String, reason: String) -> Unit)? = null
)
```

**Built-in output guard stages:**

| Stage | Order | Default | Description |
|-------|-------|---------|-------------|
| `SystemPromptLeakageOutputGuard` | 5 | opt-in | Canary token + leakage pattern detection |
| `PiiMaskingOutputGuard` | 10 | opt-in | Masks PII (phone, email, SSN, credit card) |
| `DynamicRuleOutputGuard` | 15 | opt-in | Runtime-configurable rules (REST API managed) |
| `RegexPatternOutputGuard` | 20 | opt-in | Static regex pattern filtering |

**Result types:**
- `Allowed` — response passes unchanged
- `Modified` — response content replaced (e.g., PII masked)
- `Rejected` — response blocked entirely

**Streaming mode**: Output guard runs post-completion on the fully collected content. On rejection/modification, emits `StreamEventMarker.error()` events to the SSE client.

### GuardPipeline Execution Flow

```kotlin
class GuardPipeline(
    stages: List<GuardStage>,
    private val auditPublisher: GuardAuditPublisher? = null
) : RequestGuard {

    override suspend fun guard(command: GuardCommand): GuardResult {
        var currentCommand = command
        for (stage in sortedStages) {
            when (val result = stage.check(currentCommand)) {
                is Allowed -> {
                    // Apply normalized text from hints (UnicodeNormalizationStage)
                    val norm = result.hints.firstOrNull { it.startsWith("normalized:") }
                    if (norm != null) currentCommand = currentCommand.copy(
                        text = norm.removePrefix("normalized:")
                    )
                    continue
                }
                is Rejected -> return result  // Stop immediately
            }
        }
        return GuardResult.Allowed.DEFAULT
    }
}
```

### Fail-Close Policy

Guard **always operates as Fail-Close** — both input and output:

- If an exception occurs in a guard stage -> **request is rejected**
- This ensures security is never bypassed
- This is a key difference from Hooks (Hooks default to Fail-Open)

### OWASP LLM Top 10 Coverage

| OWASP | Threat | Layer | Stage | Default |
|-------|--------|-------|-------|---------|
| LLM01 | Direct Prompt Injection | L0 | UnicodeNormalization + InjectionDetection | ON |
| LLM01 | Indirect Injection (tool) | L3 | ToolOutputSanitizer | opt-in |
| LLM01 | Multi-turn Jailbreak | L1 | TopicDriftDetection | opt-in |
| LLM02 | PII in Responses | Output | PiiMaskingOutputGuard | opt-in |
| LLM07 | System Prompt Leakage | L2 | CanaryToken + LeakageGuard | opt-in |
| LLM10 | Resource Exhaustion | L0 | Tenant-aware RateLimit | ON |

### Configuration

```yaml
arc:
  reactor:
    guard:
      enabled: true                            # Master toggle
      rate-limit-per-minute: 10                # Per-user per-minute limit
      rate-limit-per-hour: 100                 # Per-user per-hour limit
      injection-detection-enabled: true        # Regex-based injection detection
      unicode-normalization-enabled: true      # NFKC + homoglyph + zero-width
      classification-enabled: false            # Rule-based classification (opt-in)
      classification-llm-enabled: false        # LLM classification (opt-in)
      canary-token-enabled: false              # System prompt leakage protection (opt-in)
      tool-output-sanitization-enabled: false   # Indirect injection defense (opt-in)
      audit-enabled: true                      # Guard audit trail
      tenant-rate-limits:                      # Per-tenant overrides
        tenant-abc:
          per-minute: 50
          per-hour: 500
```

### Executor Integration

```kotlin
// SpringAiAgentExecutor.kt
private suspend fun checkGuard(command: AgentCommand): GuardResult.Rejected? {
    if (guard == null) return null
    val userId = command.userId ?: "anonymous"  // Defend against null userId
    val result = guard.guard(GuardCommand(userId = userId, text = command.userPrompt,
        metadata = command.metadata))
    return result as? GuardResult.Rejected
}
```

If `userId` is null, `"anonymous"` is used. The guard is never skipped (prevents security vulnerabilities).

### Customizing Guards

Register a bean with `@Component` to automatically add it to the pipeline:

```kotlin
@Component
class MyCustomGuard : PermissionStage {
    override suspend fun check(command: GuardCommand): GuardResult {
        // Allow only admins
        if (command.userId in adminList) return GuardResult.Allowed()
        return GuardResult.Rejected("Admin access only", UNAUTHORIZED)
    }
}
```

---

## Hook System

### Overview

Hooks provide extension points at **4 key moments** during agent execution.

```
BeforeAgentStart ──→ [Agent ReAct Loop] ──→ AfterAgentComplete
                           │
                    BeforeToolCall ──→ [Tool Execution] ──→ AfterToolCall
                    (repeated per tool)
```

### 4 Hook Types

| Hook | Timing | Return Value | Purpose |
|------|------|--------|------|
| `BeforeAgentStart` | Before agent starts | HookResult | Authentication, budget check, can reject |
| `BeforeToolCall` | Before tool invocation | HookResult | Per-tool permissions, parameter validation |
| `AfterToolCall` | After tool invocation | void | Result logging, metrics |
| `AfterAgentComplete` | After agent completes | void | Billing, statistics, notifications |

### AgentHook Base Interface

```kotlin
interface AgentHook {
    val order: Int get() = 0          // Execution order (lower runs first)
    val enabled: Boolean get() = true  // Whether enabled
    val failOnError: Boolean get() = false  // Behavior on failure
}
```

**Recommended order ranges:**

| Range | Purpose | Examples |
|------|------|------|
| 1-99 | Critical hooks | Authentication, security |
| 100-199 | Standard hooks | Logging, auditing |
| 200+ | Late hooks | Cleanup, notifications |

### HookResult

```kotlin
sealed class HookResult {
    data object Continue : HookResult()            // Proceed
    data class Reject(val reason: String)           // Reject execution
    data class Modify(val modifiedParams: Map<...>) // Modify parameters and proceed
    data class PendingApproval(                     // Await asynchronous approval
        val approvalId: String,
        val message: String
    )
}
```

### Hook Context

**HookContext** — Agent level:

```kotlin
data class HookContext(
    val runId: String,               // Execution ID (UUID)
    val userId: String,              // User ID
    val userEmail: String? = null,
    val userPrompt: String,          // User prompt
    val channel: String? = null,
    val startedAt: Instant,          // Start time
    val toolsUsed: MutableList<String>,          // CopyOnWriteArrayList
    val metadata: MutableMap<String, Any>        // ConcurrentHashMap
)
```

**ToolCallContext** — Tool call level:

```kotlin
data class ToolCallContext(
    val agentContext: HookContext,    // Parent agent context
    val toolName: String,            // Tool name
    val toolParams: Map<String, Any?>,  // Tool parameters
    val callIndex: Int               // Call index (0-based)
) {
    // Automatic masking of sensitive parameters
    fun maskedParams(): Map<String, Any?> {
        // password, token, secret, key, credential, apikey → "***"
    }
}
```

### HookExecutor Execution Flow

```kotlin
class HookExecutor(
    beforeStartHooks: List<BeforeAgentStartHook>,
    beforeToolCallHooks: List<BeforeToolCallHook>,
    afterToolCallHooks: List<AfterToolCallHook>,
    afterCompleteHooks: List<AfterAgentCompleteHook>
) {
    // Each type: filter enabled + sort by order
    private val sortedBeforeStartHooks = beforeStartHooks
        .filter { it.enabled }
        .sortedBy { it.order }
    // ...
}
```

**Before-type Hook execution:**

```kotlin
private suspend fun <T : AgentHook, C> executeHooks(
    hooks: List<T>,
    context: C,
    execute: suspend (T, C) -> HookResult
): HookResult {
    for (hook in hooks) {
        try {
            when (val result = execute(hook, context)) {
                is Continue       -> continue      // Next hook
                is Reject         -> return result  // Reject immediately
                is Modify         -> return result  // Return modification
                is PendingApproval -> return result // Await approval
            }
        } catch (e: Exception) {
            if (hook.failOnError) {
                return HookResult.Reject("Hook execution failed: ${e.message}")
            }
            // fail-open: ignore error and continue to next hook
        }
    }
    return HookResult.Continue  // All hooks passed
}
```

**After-type Hook execution:**

```kotlin
suspend fun executeAfterToolCall(context: ToolCallContext, result: ToolCallResult) {
    for (hook in sortedAfterToolCallHooks) {
        try {
            hook.afterToolCall(context, result)
        } catch (e: Exception) {
            logger.error(e) { "AfterToolCallHook failed" }
            if (hook.failOnError) throw e
        }
    }
}
```

After hooks do not return a result value. They only observe and cannot reject or modify.

### Fail-Open vs Fail-Close

| | Guard | Hook (default) | Hook (failOnError=true) |
|---|---|---|---|
| On error | Request rejected | Error ignored, continues | Exception propagated, execution halted |
| Policy | Fail-Close (fixed) | Fail-Open (default) | Fail-Close (opt-in) |
| Rationale | Security must never be bypassed | Hook failure should not block core functionality | Critical hooks should halt on failure |

### Executor Integration

**BeforeAgentStart Hook:**

```kotlin
// Called in executeInternal()
checkBeforeHooks(hookContext)?.let { hookResult ->
    val message = when (hookResult) {
        is HookResult.Reject -> hookResult.reason
        is HookResult.PendingApproval -> "Pending approval: ${hookResult.message}"
        else -> "Blocked by hook"
    }
    return AgentResult.failure(
        errorMessage = message,
        errorCode = AgentErrorCode.HOOK_REJECTED
    )
}
```

**BeforeToolCall / AfterToolCall Hook:**

Called inside parallel tool execution (`executeSingleToolCall()`). If BeforeToolCall returns Reject, **only that specific tool call** is skipped while other tools execute normally.

**AfterAgentComplete Hook:**

```kotlin
// Final step in executeInternal()
try {
    hookExecutor?.executeAfterAgentComplete(context, response)
} catch (e: Exception) {
    logger.error(e) { "AfterAgentComplete hook failed" }
    // Only logs the error, does not affect the final result
}
```

AfterAgentComplete is always called regardless of whether the agent succeeded or failed. In streaming mode, it is called in a `finally` block, ensuring it always executes even if an error occurs.

### Hook Implementation Examples

**Audit log hook:**

```kotlin
@Component
class AuditLogHook : AfterAgentCompleteHook {
    override val order = 100  // Standard hook range

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        logger.info {
            "Agent completed: runId=${context.runId}, " +
            "userId=${context.userId}, " +
            "success=${response.success}, " +
            "tools=${response.toolsUsed}, " +
            "duration=${context.durationMs()}ms"
        }
    }
}
```

**Budget check hook:**

```kotlin
@Component
class BudgetCheckHook(
    private val billingService: BillingService
) : BeforeAgentStartHook {
    override val order = 10  // Critical hook (after authentication)

    override suspend fun beforeAgentStart(context: HookContext): HookResult {
        val budget = billingService.getRemainingBudget(context.userId)
        if (budget <= 0) {
            return HookResult.Reject("Budget has been exhausted")
        }
        return HookResult.Continue
    }
}
```

---

## Guard vs Hook Comparison

```
User Request
    │
    ▼
┌─ Guard Pipeline ─────────────────────────┐
│  "Is this request safe to execute?"       │
│  - Security validation (injection, auth)  │
│  - Input validation (length, format)      │
│  - Rate limiting                          │
│  → Fail-Close: error = reject             │
└───────────────────────────────────────────┘
    │ Passed
    ▼
┌─ Hook System ─────────────────────────────┐
│  "What additional actions for this run?"   │
│  - Logging, auditing                       │
│  - Billing, metrics                        │
│  - Notifications, cleanup                  │
│  → Fail-Open: error = ignore and continue  │
└───────────────────────────────────────────┘
```

| Aspect | Guard | Hook |
|------|-------|------|
| Question | "Is this request safe?" | "What else should we do for this run?" |
| Execution timing | Before agent starts (once) | At multiple points (4 types) |
| Error policy | Always Fail-Close | Default Fail-Open, opt-in Fail-Close |
| Rejection scope | Entire request | Entire request or specific tool only |
| Modification ability | Not possible | Possible via `HookResult.Modify` |
| Number of stages | 5 stages (fixed types) | 4 types (unlimited implementations) |
