# Tool Guide

## What is a Tool?

A tool is the **means by which an agent interacts with the outside world**.

LLMs can only generate text. They cannot perform real tasks like calculations, API calls, or file reading.
When you provide tools, the LLM decides "I should use this tool" and invokes it.

```
User: "Tell me the weather in Seoul"

[Agent]
  LLM: "I should call the weather tool"
  -> getWeather({city: "Seoul"}) called
  -> "Seoul: Sunny, 22°C"
  LLM: "Seoul is currently sunny, 22 degrees."
```

Without tools, the LLM can only answer from its trained knowledge (which may be inaccurate).
With tools, **real-time data and actual operations** become possible.

---

## Three Types of Tools

There are 3 ways to create tools in Arc Reactor.

### 1. LocalTool -- Spring AI Annotation Approach (Recommended)

The simplest approach. Add the `@Tool` annotation to a class and it automatically becomes a tool.

```kotlin
@Component
class WeatherTool(
    private val weatherApi: WeatherApiClient  // Spring DI injection supported
) : LocalTool {

    override val category = DefaultToolCategory.SEARCH  // Tool classification (optional)

    @Tool(description = "도시의 현재 날씨를 조회합니다")
    fun getWeather(
        @ToolParam(description = "도시 이름 (예: Seoul)") city: String
    ): String {
        return weatherApi.getCurrentWeather(city)
    }

    @Tool(description = "일기예보를 조회합니다")
    fun getForecast(
        @ToolParam(description = "도시 이름") city: String,
        @ToolParam(description = "일수 (1~7)") days: Int
    ): String {
        return weatherApi.getForecast(city, days)
    }
}
```

**Features:**
- Just add `@Component` for automatic registration (no additional configuration needed)
- JSON schema is auto-generated from method signatures
- Spring DI allows injecting Services, Repositories, etc.
- Multiple `@Tool` methods can be defined in a single class
- `category` enables tool classification (used by ToolSelector)

> Example code: [`WeatherTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/example/WeatherTool.kt)

---

### 2. ToolCallback -- Direct Implementation Approach

Write the JSON schema yourself and implement the `call()` method.

```kotlin
@Component
class CalculatorTool : ToolCallback {

    override val name = "calculator"
    override val description = "수학 계산을 수행합니다"

    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "expression": {
              "type": "string",
              "description": "계산식 (예: 3 + 5)"
            }
          },
          "required": ["expression"]
        }
    """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val expression = arguments["expression"] as? String
            ?: return "Error: expression is required"
        // 계산 로직
        return evaluate(expression)
    }
}
```

**Features:**
- Direct control over the schema (fine-grained configuration)
- `suspend fun` -- async execution supported
- Usable without Spring DI (easier unit testing)
- Auto-registered with `@Component`, or manually registered without it

**When to use:**
- When you need fine-grained control over the schema
- When you need suspend functions (async external API calls)
- When building framework-independent tools

> Example code: [`CalculatorTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/example/CalculatorTool.kt), [`DateTimeTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/example/DateTimeTool.kt)

---

### 3. MCP Tools -- Fetched from External Servers

[MCP (Model Context Protocol)](https://modelcontextprotocol.io/) is a standard protocol for external servers to provide tools.

```bash
# Register an MCP server via admin API
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "filesystem",
    "transportType": "STDIO",
    "config": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    },
    "autoConnect": true
  }'
```

**Features:**
- Tool implementation resides on an external server (no code writing required)
- Many open-source MCP servers available (filesystem, DB, GitHub, Slack, etc.)
- Tools are automatically registered upon connection
- Supports STDIO (local process) and SSE (HTTP) transport modes

**How it works:**
```
Agent -> McpToolCallback.call() -> MCP Protocol -> External Server -> Result
```

Internally, `McpToolCallback` wraps MCP tools with the `ToolCallback` interface,
so from the agent's perspective, they are used identically to local tools.

---

## Comparison of All Three

| | LocalTool | ToolCallback | MCP Tool |
|---|---|---|---|
| **Implementation location** | Same project | Same project | External server |
| **Schema generation** | Automatic (`@Tool` annotation) | Manual (write JSON) | Automatic (provided by server) |
| **Spring DI** | Supported | Supported | N/A |
| **Async** | Not supported (Spring AI constraint) | Supported (`suspend fun`) | Supported |
| **Registration method** | `@Component` | `@Component` or manual | `mcpManager.connect()` |
| **When to use** | Most cases | When fine-grained control is needed | When connecting to external services |

**Recommendation:** In most cases, use the **LocalTool** approach. It is the simplest and integrates best with the Spring ecosystem.

---

## How an Agent Uses Tools

```
1. Tool Collection
   LocalTool list (auto-discovered via @Component)
   + ToolCallback list (auto-discovered via @Component)
   + MCP tool list (fetched from McpManager)
      |
2. Tool Filtering (ToolSelector)
   Analyzes the user prompt and selects only relevant tools
   e.g., "Tell me the weather" -> selects only SEARCH category tools
      |
3. Pass Tool List to LLM
   Tool names + descriptions + schemas are included in the LLM context
      |
4. LLM Decides to Call a Tool
   "I should call getWeather with city=Seoul"
      |
5. Tool Execution
   BeforeToolCall hook -> call() execution -> AfterToolCall hook
      |
6. Return Result to LLM
   LLM generates the final response based on the tool result
```

---

## Tool Classification (ToolCategory)

When you have many tools, passing all of them to the LLM is inefficient.
By specifying a `ToolCategory`, **only tools relevant to the user prompt** are passed to the LLM.

```kotlin
class OrderTool : LocalTool {
    override val category = DefaultToolCategory.SEARCH  // Matches "search", "find", "query" keywords

    @Tool(description = "주문 조회")
    fun getOrder(orderId: String): String { ... }
}
```

Built-in categories:

| Category | Matching Keywords |
|----------|-------------------|
| `SEARCH` | 검색, search, 찾아, find, 조회, query |
| `CREATE` | 생성, create, 만들어, 작성, write |
| `ANALYZE` | 분석, analyze, 요약, summary, 리포트 |
| `COMMUNICATE` | 전송, send, 메일, email, 알림, notify |
| `DATA` | 데이터, data, 저장, save, 업데이트 |

If `category = null`, the tool is always selected (not subject to filtering).

You can also create custom categories:

```kotlin
object FinanceCategory : ToolCategory {
    override val name = "FINANCE"
    override val keywords = setOf("결제", "payment", "환불", "refund", "잔액", "balance")
}

class PaymentTool : LocalTool {
    override val category = FinanceCategory
    ...
}
```

---

## Tool Registration Methods Summary

### Automatic Registration (Recommended)

Just add `@Component` and Spring will automatically discover and register it with the agent.

```kotlin
@Component  // This is all you need
class MyTool : LocalTool { ... }

@Component
class AnotherTool : ToolCallback { ... }
```

### Manual Registration

Register as a bean directly without `@Component`, or pass tools to a multi-agent node.

```kotlin
// Method 1: Register via @Bean
@Configuration
class ToolConfig {
    @Bean
    fun calculatorTool(): ToolCallback = CalculatorTool()
}

// Method 2: Pass directly to a multi-agent node
MultiAgent.supervisor()
    .node("refund") {
        tools = listOf(CheckOrderTool(), ProcessRefundTool())  // Tools exclusive to this node
    }
```

### MCP Tool Registration

Register MCP servers via the admin REST API:

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "github",
    "transportType": "STDIO",
    "config": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"]
    },
    "autoConnect": true
  }'
```

---

## Tool Execution Results (ToolResult)

When a tool needs to return structured results, you can use `ToolResult`.

```kotlin
override suspend fun call(arguments: Map<String, Any?>): Any {
    val orderId = arguments["orderId"] as? String
        ?: return SimpleToolResult.failure("orderId is required")

    val order = orderRepository.findById(orderId)
        ?: return SimpleToolResult.failure("Order not found: $orderId")

    return SimpleToolResult.success(
        message = "주문 조회 성공",
        data = mapOf("orderId" to order.id, "status" to order.status)
    )
}
```

Returning a plain string is also fine:

```kotlin
override suspend fun call(arguments: Map<String, Any?>): Any {
    return "Seoul: Sunny, 22°C"  // String is OK too
}
```

---

## Tools in Multi-Agent Systems

In the multi-agent Supervisor pattern, **agents themselves become tools**.

```
Supervisor agent's tool list:
  - calculator          <- Regular tool (ToolCallback)
  - getWeather          <- Local tool (LocalTool)
  - file_read           <- MCP tool (external server)
  - delegate_to_refund  <- WorkerAgentTool (agent wrapped as a tool)
```

From the agent's perspective, all four are identical tools. For details, see the [Supervisor Pattern Guide](../architecture/supervisor-pattern.md).

---

## Reference Code

| File | Description |
|------|-------------|
| [`ToolCallback.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/ToolCallback.kt) | Tool interface |
| [`LocalTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/LocalTool.kt) | Spring AI annotation marker |
| [`ToolCategory.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/ToolCategory.kt) | Tool classification system |
| [`ToolSelector.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/ToolSelector.kt) | Tool filtering |
| [`McpManager.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/mcp/McpManager.kt) | MCP server management |
| [`CalculatorTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/example/CalculatorTool.kt) | ToolCallback implementation example |
| [`WeatherTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/example/WeatherTool.kt) | LocalTool implementation example |
| [`WorkerAgentTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/agent/multi/WorkerAgentTool.kt) | Adapter that wraps an agent as a tool |
