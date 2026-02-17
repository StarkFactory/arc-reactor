# Guard Pipeline & Hook System

> This document explains the internal workings of Arc Reactor's security layer (Guard) and lifecycle extension points (Hook).

## Guard System

### Overview

Guard is a 5-stage security pipeline that validates all incoming requests **before execution** of the AI agent.

```
Request → [RateLimit] → [InputValidation] → [InjectionDetection] → [Classification] → [Permission] → Pass
            order=1        order=2              order=3                order=4            order=5
```

If any stage returns `Rejected`, the pipeline stops immediately and returns a failure without executing the agent.

### Core Interfaces

**RequestGuard** (`guard/Guard.kt`):

```kotlin
interface RequestGuard {
    suspend fun guard(command: GuardCommand): GuardResult
}
```

**GuardStage** — Individual check stage:

```kotlin
interface GuardStage {
    val stageName: String    // Stage name (for logging/error messages)
    val order: Int           // Execution order (lower runs first)
    val enabled: Boolean     // Can be disabled

    suspend fun check(command: GuardCommand): GuardResult
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

**Rejection categories:**

| Category | Description |
|---------|------|
| `RATE_LIMITED` | Rate limit exceeded |
| `INVALID_INPUT` | Invalid input (empty string, length exceeded) |
| `PROMPT_INJECTION` | Prompt injection attack detected |
| `OFF_TOPIC` | Unrelated request |
| `UNAUTHORIZED` | Insufficient permissions |
| `SYSTEM_ERROR` | Error within the guard stage itself |

### 5-Stage Pipeline

#### Stage 1: RateLimit (order=1)

```kotlin
class DefaultRateLimitStage(
    private val requestsPerMinute: Int = 10,
    private val requestsPerHour: Int = 100
) : RateLimitStage
```

- Tracks per-user request counts using a Caffeine cache
- Applies two windows: per-minute and per-hour
- Cache TTL automatically resets counters

#### Stage 2: InputValidation (order=2)

```kotlin
class DefaultInputValidationStage(
    private val maxLength: Int = 10000,
    private val minLength: Int = 1
) : InputValidationStage
```

- Rejects empty strings
- Rejects input exceeding maximum length (default 10,000 characters)

#### Stage 3: InjectionDetection (order=3)

```kotlin
class DefaultInjectionDetectionStage : InjectionDetectionStage
```

Detects prompt injection attacks using regex patterns:

- **Role change attempts:** `"ignore previous"`, `"you are now"`, `"act as"`
- **System prompt extraction:** `"show me your prompt"`, `"repeat your instructions"`
- **Output manipulation:** `"output the following"`, `"print exactly"`
- **Encoding bypass:** `"base64"`, `"rot13"`, `"hex encode"`
- **Delimiter injection:** `###`, `---`, `===`, `<<<>>>`

#### Stage 4: Classification (order=4)

```kotlin
class DefaultClassificationStage : ClassificationStage
```

The default implementation is a pass-through (allows all requests). In production, implement LLM-based or rule-based content classification.

#### Stage 5: Permission (order=5)

```kotlin
class DefaultPermissionStage : PermissionStage
```

The default implementation is a pass-through (allows all users). In production, integrate RBAC or a custom permission system.

### GuardPipeline Execution Flow

```kotlin
class GuardPipeline(stages: List<GuardStage>) : RequestGuard {

    // Only enabled stages, sorted by order
    private val sortedStages = stages.filter { it.enabled }.sortedBy { it.order }

    override suspend fun guard(command: GuardCommand): GuardResult {
        if (sortedStages.isEmpty()) return GuardResult.Allowed.DEFAULT

        for (stage in sortedStages) {
            try {
                when (val result = stage.check(command)) {
                    is Allowed  -> continue        // Next stage
                    is Rejected -> return result    // Stop immediately
                }
            } catch (e: Exception) {
                // Fail-Close: Reject on error
                return GuardResult.Rejected(
                    reason = "Security check failed",
                    category = RejectionCategory.SYSTEM_ERROR,
                    stage = stage.stageName
                )
            }
        }

        return GuardResult.Allowed.DEFAULT  // All stages passed
    }
}
```

### Fail-Close Policy

Guard **always operates as Fail-Close**:

- If an exception occurs in a guard stage -> **request is rejected**
- This ensures security is never bypassed
- This is a key difference from Hooks (Hooks default to Fail-Open)

### Executor Integration

```kotlin
// SpringAiAgentExecutor.kt
private suspend fun checkGuard(command: AgentCommand): GuardResult.Rejected? {
    if (guard == null) return null
    val userId = command.userId ?: "anonymous"  // Defend against null userId
    val result = guard.guard(GuardCommand(userId = userId, text = command.userPrompt))
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
