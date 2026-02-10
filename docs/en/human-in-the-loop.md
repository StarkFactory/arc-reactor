# Human-in-the-Loop (HITL)

## One-Line Summary

**Pause certain tool calls for human approval before execution, giving humans control over high-risk actions.**

---

## Why Human-in-the-Loop?

AI agents are powerful, but some actions should not be fully automated:

```
User: "Cancel all orders from last month"

[Agent without HITL]
  LLM: "I should call cancelOrder for each order"
  -> cancelOrder({orderId: "1001"}) -> Cancelled
  -> cancelOrder({orderId: "1002"}) -> Cancelled
  -> cancelOrder({orderId: "1003"}) -> Cancelled
  ... (all cancelled, no way to undo)
```

With HITL, the agent pauses before executing dangerous actions:

```
User: "Cancel all orders from last month"

[Agent with HITL]
  LLM: "I should call cancelOrder for order #1001"
  -> cancelOrder requires approval
  -> Agent suspends, waiting for human
  -> Human reviews: "Cancel order #1001? [Approve] [Reject]"
  -> Human approves
  -> cancelOrder({orderId: "1001"}) -> Cancelled
  -> Next order...
```

The human stays in control of irreversible or high-value operations.

---

## Core Concepts

### ToolApprovalPolicy

An interface that decides which tool calls need human approval:

```kotlin
interface ToolApprovalPolicy {
    fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean
}
```

Return `true` to pause the tool call for approval, `false` to let it execute immediately.

### PendingApprovalStore

Manages pending approvals using Kotlin `CompletableDeferred`. When a tool call requires approval:

1. A `PendingApproval` entry is created with a unique ID
2. The agent's coroutine suspends on the `CompletableDeferred`
3. When a human approves/rejects via REST API, the `CompletableDeferred` completes
4. The agent resumes: executes the tool (if approved) or skips it (if rejected)

### ApprovalController

REST API for humans to view and act on pending approvals.

---

## Configuration

### Enable HITL

```yaml
arc:
  reactor:
    approval:
      enabled: true               # Enable HITL (default: false)
      timeout-ms: 300000          # Approval timeout in ms (default: 5 minutes)
      tool-names:                 # Tools that require approval (used by ToolNameApprovalPolicy)
        - delete_order
        - process_refund
        - send_payment
```

### Properties

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Master switch for HITL |
| `timeout-ms` | `300000` | How long to wait for approval before timing out (ms) |
| `tool-names` | `[]` | List of tool names requiring approval (for built-in policy) |

---

## Execution Flow

```
User prompt: "Refund order #1234"
|
v
[Agent ReAct Loop]
  LLM decides to call: process_refund({orderId: "1234", amount: 50000})
     |
     v
  ToolApprovalPolicy.requiresApproval("process_refund", {orderId: "1234", ...})
     -> true (process_refund is in the approval list)
     |
     v
  PendingApprovalStore.requestApproval()
     -> Creates PendingApproval(id="abc-123", toolName="process_refund", ...)
     -> Agent coroutine suspends on CompletableDeferred
     |
     v
  [Human reviews via REST API]
     GET /api/approvals -> sees pending approval "abc-123"
     |
     v
  [Human decides]
     POST /api/approvals/abc-123/approve
     or
     POST /api/approvals/abc-123/reject?reason=Duplicate refund
     |
     v
  CompletableDeferred completes
     -> Agent coroutine resumes
     |
     v
  [If approved] process_refund executes -> "Refund of 50,000 completed"
  [If rejected] Tool is skipped -> LLM told "Tool call was rejected by operator: Duplicate refund"
     |
     v
  LLM generates final response
```

---

## REST API

### List Pending Approvals

```bash
GET /api/approvals
```

Response:
```json
[
  {
    "id": "abc-123",
    "toolName": "process_refund",
    "arguments": {
      "orderId": "1234",
      "amount": 50000
    },
    "requestedAt": "2026-02-10T14:30:00Z",
    "sessionId": "session-456",
    "userPrompt": "Refund order #1234"
  }
]
```

### Approve a Tool Call

```bash
POST /api/approvals/abc-123/approve
Content-Type: application/json

{
  "modifiedArguments": {
    "orderId": "1234",
    "amount": 25000
  }
}
```

The optional `modifiedArguments` field lets the human modify the tool arguments before execution. For example, approving a refund but reducing the amount.

If omitted, the original arguments are used.

### Reject a Tool Call

```bash
POST /api/approvals/abc-123/reject
Content-Type: application/json

{
  "reason": "This order was already refunded"
}
```

The optional `reason` is passed back to the LLM so it can inform the user.

---

## Built-in Policies

### ToolNameApprovalPolicy (Default)

Requires approval for tools whose names are in the `tool-names` configuration list:

```yaml
arc:
  reactor:
    approval:
      enabled: true
      tool-names:
        - delete_order
        - process_refund
        - send_payment
```

Any tool not in the list executes immediately without approval.

### AlwaysApprovePolicy

Never requires approval. Effectively disables HITL while keeping the infrastructure active. Useful for testing:

```kotlin
@Bean
fun approvalPolicy(): ToolApprovalPolicy = AlwaysApprovePolicy()
```

---

## Custom Policies

Implement `ToolApprovalPolicy` to define your own approval logic:

### Amount-Based Policy

```kotlin
@Component
class AmountPolicy : ToolApprovalPolicy {
    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
        val amount = (arguments["amount"] as? Number)?.toDouble() ?: return false
        return amount > 10_000  // Require approval for amounts over 10,000
    }
}
```

### Role-Based Policy

```kotlin
@Component
class RoleBasedPolicy(
    private val userStore: UserStore
) : ToolApprovalPolicy {
    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
        // Could check user role, tool name, argument values, etc.
        val dangerousTools = setOf("delete_order", "process_refund", "modify_account")
        return toolName in dangerousTools
    }
}
```

### Composite Policy

```kotlin
@Component
class CompositePolicy(
    private val policies: List<ToolApprovalPolicy>
) : ToolApprovalPolicy {
    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
        // Require approval if ANY policy says so
        return policies.any { it.requiresApproval(toolName, arguments) }
    }
}
```

Thanks to `@ConditionalOnMissingBean`, providing your own `ToolApprovalPolicy` bean replaces the default `ToolNameApprovalPolicy`.

---

## Timeout Behavior

If no human responds within `timeout-ms`:

```
Agent suspends → Timeout reached → TimeoutException
  → Tool call is skipped
  → LLM receives: "Tool call timed out waiting for approval"
  → LLM informs the user
```

The timeout prevents agents from hanging indefinitely. Adjust `timeout-ms` based on your operational workflow (e.g., longer for async workflows, shorter for real-time chat).

---

## Fail-Open Design

HITL is designed to be fail-open: if the approval system itself encounters an error, the tool executes normally rather than blocking the agent.

| Scenario | Behavior |
|----------|----------|
| `approval.enabled = false` | All tools execute without approval |
| `PendingApprovalStore` throws exception | Tool executes normally (logs warning) |
| Approval timeout | Tool call is skipped, LLM informed |
| Human approves | Tool executes with original or modified arguments |
| Human rejects | Tool is skipped, rejection reason sent to LLM |

The principle: **a broken approval system should not break the agent**.

---

## Integration with Multi-Agent

In the Supervisor pattern, HITL applies at the tool execution level. Both the Supervisor and Worker agents respect the approval policy:

```
[Supervisor] -> delegate_to_refund (WorkerAgentTool, no approval needed)
  [Refund Worker] -> process_refund (requires approval)
    -> Human approves
    -> Refund executes
  [Refund Worker] -> send_notification (no approval needed)
    -> Executes immediately
```

The approval check happens in `SpringAiAgentExecutor.checkToolApproval()`, which runs for every tool call regardless of the agent's position in the hierarchy.

---

## Reference Code

| File | Description |
|------|-------------|
| [`ToolApprovalPolicy.kt`](../../src/main/kotlin/com/arc/reactor/approval/ToolApprovalPolicy.kt) | Policy interface + built-in implementations |
| [`ApprovalModels.kt`](../../src/main/kotlin/com/arc/reactor/approval/ApprovalModels.kt) | PendingApproval, ApprovalResult data classes |
| [`PendingApprovalStore.kt`](../../src/main/kotlin/com/arc/reactor/approval/PendingApprovalStore.kt) | CompletableDeferred-based approval store |
| [`ApprovalController.kt`](../../src/main/kotlin/com/arc/reactor/controller/ApprovalController.kt) | REST API for approval management |
| [`SpringAiAgentExecutor.kt`](../../src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt) | checkToolApproval() integration point |
| [`AgentProperties.kt`](../../src/main/kotlin/com/arc/reactor/agent/config/AgentProperties.kt) | ApprovalProperties configuration |
