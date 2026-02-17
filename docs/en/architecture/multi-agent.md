# Multi-Agent Guide

## What is Multi-Agent?

Instead of a single AI agent handling everything, **multiple specialized agents collaborate** in a coordinated structure.

To put it in human terms:
- **Single agent** = one employee who handles consultation, orders, and refunds all by themselves
- **Multi-agent** = a team lead receives requests and delegates them to the order team / refund team / shipping team

## Why is it Needed?

| Situation | Single Agent | Multi-Agent |
|-----------|-------------|------------|
| More than 20 tools | Confusion, wrong tool selection | 3-5 tools per agent, cleanly separated |
| Different expertise required | System prompt becomes bloated | Specialized prompt per agent |
| Independent tasks to run simultaneously | Sequential execution, slow | Parallel execution, fast |
| Need to pass results to next step | Requires prompt engineering | Automatic pipelining |

---

## 3 Patterns

Arc Reactor supports three multi-agent patterns.

### 1. Sequential (Pipeline)

```
[Agent A] -> result -> [Agent B] -> result -> [Agent C] -> final result
```

A chain structure where **A's output becomes B's input**.

**Real-world example -- Blog post writing:**
1. Research agent: investigate "AI Trends 2026" -> outputs research results
2. Writing agent: receives research results -> writes a blog post
3. Editing agent: receives the blog post -> corrects grammar/style

```kotlin
val result = MultiAgent.sequential()
    .node("researcher") {
        systemPrompt = "Research key trends on the given topic"
    }
    .node("writer") {
        systemPrompt = "Write a blog post based on the research results"
    }
    .node("editor") {
        systemPrompt = "Correct the grammar and style of the article"
    }
    .execute(command, agentFactory)
```

**How it works:**
- The first node receives the user's original input (`userPrompt`)
- From the second node onward, the **previous node's output** is passed as the `userPrompt`
- If any node fails, execution stops immediately (e.g., C will not run)

---

### 2. Parallel (Concurrent Execution)

```
         +-> [Agent A] -> Result A -+
User ->  +-> [Agent B] -> Result B -+-> Merge Results -> Final Result
         +-> [Agent C] -> Result C -+
```

**Multiple agents process the same input simultaneously**, and the results are merged.

**Real-world example -- Code review:**
- Security agent: analyzes security vulnerabilities like SQL injection, XSS
- Style agent: checks coding conventions and naming
- Logic agent: verifies business logic correctness

Since the three analyses are independent, **running them concurrently** makes it 3x faster.

```kotlin
val result = MultiAgent.parallel()
    .node("security") {
        systemPrompt = "Analyze the code for security vulnerabilities"
    }
    .node("style") {
        systemPrompt = "Check coding conventions"
    }
    .node("logic") {
        systemPrompt = "Verify business logic"
    }
    .execute(command, agentFactory)

// result.finalResult.content -> a combined string of all three results
```

**Options:**
- `failFast = true`: if any agent fails, the entire execution fails
- `failFast = false` (default): only successful results are collected and returned
- `merger`: customize how results are merged

```kotlin
// Custom result merging
val result = MultiAgent.parallel(
    merger = ResultMerger { results ->
        results.joinToString("\n---\n") { "${it.nodeName}: ${it.result.content}" }
    }
)
```

---

### 3. Supervisor (Manager-Worker)

```
User: "Please refund my order"
         |
[Supervisor Agent]          <- Manager role
  "I should forward this to the refund team"
         | delegate_to_refund tool call
[Refund Agent]              <- Worker role
  Checks refund policy, processes refund
         | Returns result
[Supervisor Agent]
  "Your refund has been completed" -> Final response to user
```

A structure where the **manager evaluates the situation and delegates to the appropriate worker**.

**Real-world example -- Customer support center:**
- Supervisor: analyzes customer requests and routes them to the appropriate team
- Order worker: order lookup, modification, cancellation
- Refund worker: refund requests, status checks
- Shipping worker: shipment tracking, address changes

```kotlin
val result = MultiAgent.supervisor()
    .node("order") {
        systemPrompt = "Handle order-related tasks"
        description = "Order lookup, modification, cancellation"  // Supervisor reads this to decide
    }
    .node("refund") {
        systemPrompt = "Handle refund tasks"
        description = "Refund requests, status checks"
    }
    .node("shipping") {
        systemPrompt = "Handle shipping tasks"
        description = "Shipment tracking, address changes"
    }
    .execute(command, agentFactory)
```

---

## Supervisor Pattern Core Design

This is the most important design aspect of Arc Reactor's multi-agent system.

### Core Principle: Zero Modification to Existing Code

`SpringAiAgentExecutor` already has a **ReAct loop**:

```
User input -> LLM call -> Tool call -> LLM call -> Tool call -> ... -> Final response
```

The key idea behind the Supervisor pattern is:

> **Wrap worker agents as "tools", and the existing ReAct loop naturally invokes the workers.**

### WorkerAgentTool -- Converting an Agent into a Tool

```kotlin
class WorkerAgentTool(node, agentExecutor) : ToolCallback {
    name = "delegate_to_${node.name}"     // e.g., "delegate_to_refund"
    description = "Handle refund tasks"    // node.description

    fun call(arguments) {
        val instruction = arguments["instruction"]  // Instruction passed by the Supervisor
        val result = agentExecutor.execute(          // Execute the worker agent
            systemPrompt = node.systemPrompt,
            userPrompt = instruction
        )
        return result.content  // Return the worker's response to the Supervisor
    }
}
```

### Detailed Execution Flow

```
1. SupervisorOrchestrator.execute() is called

2. Each worker node is converted into a WorkerAgentTool
   - AgentNode("order", ...) -> WorkerAgentTool(name="delegate_to_order")
   - AgentNode("refund", ...) -> WorkerAgentTool(name="delegate_to_refund")

3. Supervisor agent is created
   - System prompt: "Delegate to the appropriate worker"
   - Tool list: [delegate_to_order, delegate_to_refund]  <- WorkerAgentTools

4. Supervisor's ReAct loop starts (using the existing SpringAiAgentExecutor as-is!)
   -> LLM: "This is a refund request, I should delegate to refund"
   -> Tool call: delegate_to_refund(instruction="Process refund for ORD-123")
     -> WorkerAgentTool.call() executes
       -> refund agent's execute() runs (also using the existing executor as-is!)
         -> refund agent processes the refund using its own tools (checkOrder, processRefund)
       -> Returns "Refund completed"
   -> LLM: "Dear customer, your refund has been completed"
   -> Final response
```

### Why is This Design Good?

1. **No existing code changes**: `SpringAiAgentExecutor` is not modified at all
2. **Natural integration**: Leverages the ReAct loop's existing "tool call" mechanism
3. **Recursive extensibility**: Worker agents can have their own tools
4. **Supervisor's judgment**: The LLM selects the appropriate worker based on context (not hardcoded)

---

## 3 Types of Tools

Agents can use 3 types of tools. **From the agent's perspective, all three are identical tools.**
It reads the name, reads the description, calls `call()`, and gets a result back.

The difference lies in **what happens internally**:

```
Agent's tool list:
  - calculator            <- Local tool (executes a single function)
  - file_read             <- MCP tool (sends request to external server)
  - delegate_to_refund    <- WorkerAgentTool (runs an entire agent)
```

| | Local Tool | MCP Tool | WorkerAgentTool |
|---|---|---|---|
| **Internal behavior** | Executes a single function | Network request to external server | Runs an entire agent (its own LLM + tools) |
| **LLM calls** | None | None | **Yes** (its own ReAct loop) |
| **Execution location** | Same process | External MCP server | Same process |
| **Example** | Calculate `3+5` -> `"8"` | File read request to MCP server -> file contents | Refund agent handles it autonomously -> `"Refund completed"` |

**MCP is about "where the tool comes from"** (local vs. external server),
**WorkerAgentTool is about "what runs inside the tool"** (simple logic vs. LLM agent).
These are concepts on different axes and can be combined together:

```
Supervisor Agent:
  Tools: [tools from MCP, WorkerAgentTools]

Refund Worker Agent:
  Tools: [payment API tool from MCP, local DB query tool]
```

### LLM Call Count Example

In the Supervisor pattern, **the LLM is called multiple times**:

```
User: "Please refund my order"

[Supervisor LLM Call #1]
  "I should call delegate_to_refund"
      |
  [Refund Worker LLM Call #1] <- Separate LLM call!
    -> Uses checkOrder tool
  [Refund Worker LLM Call #2] <- Separate LLM call!
    -> Uses processRefund tool
  [Refund Worker LLM Call #3]
    -> "Order #1234 refund completed"
      | (This string returns to the Supervisor as a tool result)

[Supervisor LLM Call #2]
  -> "Your order #1234 refund has been completed"
```

Total of 5 LLM calls: Supervisor 2 + Worker 3.
The Supervisor has no knowledge of how many LLM calls happen inside the Worker.

---

## Frequently Asked Questions

### Q: Do I need to create a new WorkerAgentTool for each worker?

**No.** `WorkerAgentTool` is a single general-purpose wrapper class. Only multiple instances are created:

```kotlin
// 1 class, 3 instances
WorkerAgentTool(refundNode, refundAgent)    // name = "delegate_to_refund"
WorkerAgentTool(orderNode, orderAgent)      // name = "delegate_to_order"
WorkerAgentTool(shippingNode, shippingAgent) // name = "delegate_to_shipping"
```

And when using the DSL builder, even this is automatic:

```kotlin
// Code the developer writes -- this is everything
MultiAgent.supervisor()
    .node("refund") { systemPrompt = "Refund specialist agent" }
    .node("shipping") { systemPrompt = "Shipping specialist agent" }
    .execute(command, agentFactory)

// Internally, WorkerAgentTool instances are automatically created and registered with the Supervisor.
```

### Q: Does the user-facing API change?

**No.** Users make requests to the same endpoint (`POST /api/chat`) and receive a single response.
Switching from single agent to multi-agent is purely an internal server implementation change.

### Q: Which pattern should I use?

- If task A's result is needed for task B -> **Sequential**
- If you want independent tasks to run fast -> **Parallel**
- If different experts are needed depending on the request type -> **Supervisor**

---

## Practical Usage: Where to Define Nodes and How to Connect Them

> Full example code: [`CustomerServiceExample.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/agent/multi/example/CustomerServiceExample.kt)

### Step 1: Define Nodes and Execute in a Service Class

```kotlin
class CustomerService(
    private val chatClient: ChatClient,
    private val properties: AgentProperties
) {
    suspend fun handle(message: String): MultiAgentResult {
        return MultiAgent.supervisor()
            // Define nodes here
            .node("order") {
                systemPrompt = "Order specialist agent"
                description = "Order lookup, modification, cancellation"  // Supervisor uses this to decide
                tools = listOf(orderLookupTool)        // Tools this worker will use
            }
            .node("refund") {
                systemPrompt = "Refund specialist agent"
                description = "Refund requests, status checks"
                tools = listOf(refundProcessTool)
            }
            // agentFactory: node -> creates an actual AgentExecutor
            .execute(
                command = AgentCommand(userPrompt = message),
                agentFactory = { node ->
                    SpringAiAgentExecutor(
                        chatClient = chatClient,
                        properties = properties,
                        toolCallbacks = node.tools,    // Node-specific tools are passed here
                        localTools = node.localTools
                    )
                }
            )
    }
}
```

### Step 2: Call from the Controller

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

### Flow Summary

```
User -> POST /api/support
  -> SupportController.support()
    -> CustomerService.handle()
      -> MultiAgent.supervisor()
        -> Define nodes (order, refund)
        -> Convert each node to an AgentExecutor via agentFactory
        -> Execute Supervisor agent (WorkerAgentTools are auto-generated)
      -> Return MultiAgentResult
    -> Return ChatResponse
  -> Response to user
```

**Key point: Define workers with `node()`, create actual executors with `agentFactory`, and run with `execute()`.**
There is no need to create a separate WorkerAgentTool class -- the orchestrator handles it automatically internally.

---

## AgentNode Configuration

Items that can be configured for each node (agent):

```kotlin
AgentNode(
    name = "refund",                    // Required: agent name
    systemPrompt = "Process refunds",   // Required: agent's role/instructions
    description = "Handles refund requests",  // Used by Supervisor when selecting a worker
    tools = listOf(myTool),             // Tools this agent will use
    localTools = listOf(myLocalTool),   // @Tool annotation-based tools
    maxToolCalls = 10                   // Maximum number of tool calls
)
```

## What is agentFactory?

`agentFactory` is a function that takes an `AgentNode` and creates an `AgentExecutor`.

```kotlin
// Basic pattern: directly create a SpringAiAgentExecutor
val result = MultiAgent.sequential()
    .node("A") { systemPrompt = "..." }
    .execute(command) { node ->
        SpringAiAgentExecutor(
            chatModel = chatModel,
            tools = node.tools,
            // ... other configuration
        )
    }

// Spring DI pattern: use a builder registered as a bean
val result = MultiAgent.supervisor()
    .node("order") { systemPrompt = "Handle orders" }
    .node("refund") { systemPrompt = "Handle refunds" }
    .execute(command) { node ->
        agentExecutorBuilder.build(node)  // Factory method
    }
```

## MultiAgentResult

Information available from the execution result:

```kotlin
val result: MultiAgentResult = orchestrator.execute(...)

result.success              // Overall success status
result.finalResult          // AgentResult (final response)
result.finalResult.content  // Final response text
result.nodeResults          // List of results per node
result.totalDurationMs      // Total execution time (ms)

// Check results per node
result.nodeResults.forEach { nodeResult ->
    nodeResult.nodeName     // Node name
    nodeResult.result       // AgentResult
    nodeResult.durationMs   // Execution time for this node
}
```

## Pattern Selection Guide

| Situation | Recommended Pattern |
|-----------|-------------------|
| Task A's result is needed for task B | Sequential |
| Run multiple analyses simultaneously | Parallel |
| Different experts needed based on user request | Supervisor |
| Research -> Writing -> Editing pipeline | Sequential |
| Security + Style + Logic code review | Parallel |
| Customer support center (orders/refunds/shipping) | Supervisor |
