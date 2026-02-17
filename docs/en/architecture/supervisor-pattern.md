# Supervisor Pattern Deep Dive

## One-Line Summary

**A manager agent assesses the situation and delegates tasks to specialized worker agents.**

---

## Why Is It Needed?

Giving a single agent all the tools causes problems:

```
Single agent's tool list (20+):
  checkOrder, cancelOrder, modifyOrder,
  processRefund, refundStatus, refundPolicy,
  trackShipping, changeAddress, deliverySchedule,
  getBalance, chargeCard, issueCredit,
  ...
```

- The LLM is more likely to select the wrong tool
- The system prompt becomes bloated ("Handle orders like this, refunds like this, shipping like this...")
- Changes in one domain can affect other domains

The Supervisor pattern solves this:

```
[Supervisor] Only sees 3 tools
  - delegate_to_order   -> Order agent (3 tools)
  - delegate_to_refund  -> Refund agent (3 tools)
  - delegate_to_shipping -> Shipping agent (3 tools)
```

Each agent has **only the tools for its own specialty** and receives **a prompt focused on its own role**.

---

## The Core Trick: WorkerAgentTool

The entire Supervisor pattern comes down to one idea:

> **Wrap an agent as a tool.**

### Regular Tool vs WorkerAgentTool

```
[Regular Tool - CalculatorTool]
  call({expression: "3+5"})
  -> Execute function
  -> Return "8"

[WorkerAgentTool - delegate_to_refund]
  call({instruction: "Process the order refund"})
  -> An entire agent runs (its own LLM + its own tools + its own ReAct loop)
  -> Return "Refund completed"
```

From the Supervisor's perspective, **both are just tools**. It reads the name, reads the description, calls `call()`, and gets a string back. It has no idea what happens inside.

### Why Is This Good?

**Not a single line of existing code needs to change.**

`SpringAiAgentExecutor` already has a ReAct loop:
```
Input -> LLM -> Tool call -> LLM -> Tool call -> ... -> Final response
```

WorkerAgentTool slots an agent into that "tool call" position.
From the executor's perspective, it is calling a tool as usual -- the fact that the tool internally runs an agent is none of the executor's concern.

---

## Full Execution Flow

```
User: "Please refund order #1234"
|
v
[Supervisor Agent] --- SpringAiAgentExecutor (existing code, unchanged)
|  System prompt: "Delegate to the appropriate worker"
|  Tool list:
|    - delegate_to_order     (WorkerAgentTool)
|    - delegate_to_refund    (WorkerAgentTool)
|    - delegate_to_shipping  (WorkerAgentTool)
|
|  [LLM Call #1] "This is a refund request, delegate to refund"
|  -> delegate_to_refund({instruction: "Process refund for order #1234"})
|     |
|     v
|     [Refund Worker Agent] --- SpringAiAgentExecutor (separate instance)
|     |  System prompt: "Process according to refund policy"
|     |  Tools: checkOrder, processRefund
|     |
|     |  [LLM Call #2] "First, let me check the order"
|     |  -> checkOrder({orderId: "1234"}) -> "Order exists, payment 50,000 KRW"
|     |
|     |  [LLM Call #3] "Now process the refund"
|     |  -> processRefund({orderId: "1234"}) -> "Refund completed"
|     |
|     |  [LLM Call #4] Summarize results
|     |  -> "Order #1234, 50,000 KRW refund processed"
|     |
|     v
|  (This string comes back as the tool result)
|
|  [LLM Call #5] Generate final answer
|  -> "Your refund of 50,000 KRW for order #1234 has been completed."
|
v
Response to user
```

Total of 5 LLM calls: Supervisor 2 + Refund Worker 3.

---

## Practical Code: How to Use It

### Defining Nodes + Execution

```kotlin
class CustomerService(
    private val chatClient: ChatClient,
    private val properties: AgentProperties
) {
    // Actual tools (implemented by the developer)
    private val checkOrderTool = CheckOrderTool(orderRepository)
    private val processRefundTool = ProcessRefundTool(paymentService)
    private val trackShippingTool = TrackShippingTool(shippingApi)

    suspend fun handle(message: String): MultiAgentResult {
        return MultiAgent.supervisor()
            .node("order") {
                systemPrompt = "Handle order lookup, modification, and cancellation"
                description = "Order-related tasks"
                tools = listOf(checkOrderTool)
            }
            .node("refund") {
                systemPrompt = "Process refunds according to refund policy"
                description = "Refund requests and status checks"
                tools = listOf(checkOrderTool, processRefundTool)
            }
            .node("shipping") {
                systemPrompt = "Handle shipment tracking and address changes"
                description = "Shipping-related tasks"
                tools = listOf(trackShippingTool)
            }
            .execute(
                command = AgentCommand(
                    systemPrompt = "Analyze the customer request and delegate to the appropriate team",
                    userPrompt = message
                ),
                agentFactory = { node ->
                    SpringAiAgentExecutor(
                        chatClient = chatClient,
                        properties = properties,
                        toolCallbacks = node.tools
                    )
                }
            )
    }
}
```

### Connecting the Controller

```kotlin
@RestController
class SupportController(private val customerService: CustomerService) {

    @PostMapping("/api/support")
    suspend fun support(@RequestBody request: ChatRequest): ChatResponse {
        val result = customerService.handle(request.message)
        return ChatResponse(
            content = result.finalResult.content,
            success = result.success
        )
    }
}
```

Users only call `POST /api/support`. They have no idea that a multi-agent system is running behind the scenes.

---

## What Happens Under the Hood (The Part Developers Don't Touch)

When a developer calls `MultiAgent.supervisor().node(...).execute(...)`:

```
1. SupervisorOrchestrator.execute() runs

2. Each node is automatically converted to a WorkerAgentTool
   node("order")    -> WorkerAgentTool(name="delegate_to_order")
   node("refund")   -> WorkerAgentTool(name="delegate_to_refund")
   node("shipping") -> WorkerAgentTool(name="delegate_to_shipping")

3. Supervisor agent is created
   Tool list = [the 3 WorkerAgentTools created above]
   System prompt = auto-generated (or custom)

4. Supervisor's ReAct loop starts
   The LLM reads the descriptions and calls the appropriate delegate_to_* tool
   -> The worker agent runs inside WorkerAgentTool.call()
   -> The result comes back to the Supervisor
   -> The Supervisor generates the final answer
```

There is only one `WorkerAgentTool` class. Instances are created -- one per node.
Developers never need to interact with `WorkerAgentTool` directly.

---

## Relationship with MCP Tools

There are 3 types of tools an agent can use:

| | Local Tool | MCP Tool | WorkerAgentTool |
|---|---|---|---|
| **What it does internally** | Executes a single function | Sends a request to an external server | Runs an entire agent |
| **LLM calls** | None | None | Yes (its own ReAct loop) |
| **Example** | Calculator -> `"8"` | Read file from MCP server -> content | Refund agent -> `"Refund completed"` |

**MCP defines the "source of the tool"** (local vs external), **WorkerAgentTool defines the "complexity of the tool"** (function vs agent).

You can mix all three:
```
Supervisor tools: [delegate_to_refund(WorkerAgentTool), search(MCP tool)]
Refund Worker tools: [processRefund(local tool), paymentApi(MCP tool)]
```

---

## What Humans Decide vs What the LLM Decides

```
What humans (developers) decide:
  - Which workers exist (node definitions)
  - Each worker's role and tools (systemPrompt, tools)
  - Pattern selection (supervisor, sequential, parallel)

What the LLM handles on its own:
  - Which worker to route a given request to (routing)
  - What order to use tools within a worker
  - Final answer generation
```

**Node definitions must be done by humans.** This is the same across any AI Agent framework.

The reasons:
- **Tools are code** -- functions like `processRefund()` need someone to implement them
- **Permission boundaries** -- which agents can access which tools must be controlled by humans
- **Cost control** -- if agents could create agents infinitely, LLM call costs would explode

Humans define **"what capabilities this system has"**,
and the LLM decides **"when and how to use those capabilities"**.

---

## Reference Code

- [`WorkerAgentTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/agent/multi/WorkerAgentTool.kt) -- Adapter that wraps an agent as a tool
- [`SupervisorOrchestrator.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/agent/multi/SupervisorOrchestrator.kt) -- Supervisor orchestrator
- [`MultiAgentBuilder.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/agent/multi/MultiAgentBuilder.kt) -- DSL builder
- [`CustomerServiceExample.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/agent/multi/example/CustomerServiceExample.kt) -- Practical usage example
