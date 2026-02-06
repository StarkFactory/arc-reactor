# Arc Reactor

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-purple.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-orange.svg)](https://spring.io/projects/spring-ai)

**Lightweight AI Agent Framework for Spring Boot**

Arc Reactor is a production-ready AI Agent framework built on Spring AI. It provides a complete toolkit for building autonomous AI agents with guardrails, tools, memory, and RAG capabilities.

## Features

- **ReAct Pattern** - Autonomous Thought -> Action -> Observation execution loop
- **5-Stage Guard** - Rate Limit -> Input Validation -> Injection Detection -> Classification -> Permission
- **Dynamic Tools** - Local Tools with `@Tool` annotation + MCP (Model Context Protocol) support
- **Hook System** - 4 lifecycle extension points for logging, audit, and customization
- **RAG Pipeline** - Query Transform -> Retrieve -> Rerank -> Context Build
- **Memory** - Multi-turn conversation context management with In-Memory and PostgreSQL backends
- **Context Window Management** - Token-based message trimming to prevent context overflow
- **LLM Retry** - Exponential backoff with jitter for transient errors (rate limit, timeout, 5xx)
- **Parallel Tool Execution** - Concurrent tool calls via Kotlin coroutines
- **Structured Output** - JSON response mode with optional schema validation
- **Spring Boot Auto-Configuration** - Zero-config setup with sensible defaults

## Quick Start

### 1. Add Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.arc:arc-reactor:1.0.0")

    // Choose your LLM provider
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    // or
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    // or
    implementation("org.springframework.ai:spring-ai-starter-model-vertex-ai-gemini")
}
```

### 2. Configure

```yaml
# application.yml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}

arc:
  reactor:
    guard:
      enabled: true
      rate-limit-per-minute: 10
```

### 3. Create Tools

```kotlin
@Component
class CustomerTools : LocalTool {
    override val category = DefaultToolCategory.SEARCH

    @Tool(description = "Search customer information by ID")
    fun searchCustomer(@ToolParam("Customer ID") id: String): Customer {
        return customerRepository.findById(id)
    }

    @Tool(description = "Send email to customer")
    fun sendEmail(
        @ToolParam("Recipient email") to: String,
        @ToolParam("Email subject") subject: String,
        @ToolParam("Email body") body: String
    ): EmailResult {
        return emailService.send(to, subject, body)
    }
}
```

### 4. Execute Agent

```kotlin
@Service
class AgentService(
    private val agentExecutor: AgentExecutor
) {
    suspend fun chat(userMessage: String): String {
        val result = agentExecutor.execute(
            AgentCommand(
                systemPrompt = "You are a helpful customer service agent.",
                userPrompt = userMessage,
                mode = AgentMode.REACT
            )
        )
        return result.content ?: "Sorry, I couldn't process your request."
    }
}
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         User Request                             │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  GUARD PIPELINE (5 Stages)                                       │
│  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌──────────┐ ┌───────┐│
│  │RateLimit │→│InputValid│→│InjDetect  │→│Classify  │→│Permis.││
│  └──────────┘ └──────────┘ └───────────┘ └──────────┘ └───────┘│
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  HOOK: BeforeAgentStart                                          │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  AGENT EXECUTOR (ReAct Loop)                                     │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 1. Load Memory + Context Window Trimming                     ││
│  │ 2. Select Tools (LocalTool + MCP)                           ││
│  │ 3. Call LLM (with retry on transient errors)                ││
│  │ 4. Execute tool calls in parallel (with Hook lifecycle)     ││
│  │ 5. Return response or continue loop                          ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  HOOK: AfterAgentComplete                                        │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Response                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### Guard Pipeline

5-stage security guardrail that protects your agent from abuse:

| Stage | Purpose | Default Behavior |
|-------|---------|------------------|
| **RateLimit** | Prevent abuse | 10/min, 100/hour per user |
| **InputValidation** | Validate input | Max 10,000 characters |
| **InjectionDetection** | Block prompt injection | Pattern-based detection |
| **Classification** | Categorize request | Override for custom logic |
| **Permission** | Check authorization | Override for RBAC |

```kotlin
// Custom Guard Stage
@Component
class CustomClassificationStage : GuardStage {
    override val stageName = "classification"
    override val order = 30

    override suspend fun check(command: GuardCommand): GuardResult {
        if (isOffTopic(command.text)) {
            return GuardResult.Rejected(
                reason = "Off-topic request",
                category = RejectionCategory.OFF_TOPIC,
                stageName = stageName
            )
        }
        return GuardResult.Allowed.DEFAULT
    }
}
```

### Hook System

4 lifecycle hooks for extensibility:

| Hook | When | Use Case |
|------|------|----------|
| `BeforeAgentStartHook` | Before agent execution | Logging, validation, enrichment |
| `BeforeToolCallHook` | Before each tool call | Authorization, audit |
| `AfterToolCallHook` | After each tool call | Logging, metrics |
| `AfterAgentCompleteHook` | After agent completes | Audit, notification |

```kotlin
@Component
class AuditHook : AfterAgentCompleteHook {
    override val order = 100

    override suspend fun afterAgentComplete(
        context: HookContext,
        response: AgentResponse
    ) {
        auditService.log(
            userId = context.userId,
            prompt = context.userPrompt,
            response = response.response,
            toolsUsed = response.toolsUsed
        )
    }
}
```

### Context Window Management

Automatically trims conversation history to stay within the LLM's context window:

```yaml
arc:
  reactor:
    llm:
      max-context-window-tokens: 128000  # Default: 128K
      max-output-tokens: 4096
```

- Token budget = `maxContextWindowTokens - systemPromptTokens - maxOutputTokens`
- Removes oldest messages first (FIFO) when budget exceeded
- Preserves the current user prompt (never trimmed)
- Maintains AssistantMessage + ToolResponseMessage pair integrity

### LLM Retry

Automatic retry with exponential backoff for transient errors:

```yaml
arc:
  reactor:
    retry:
      max-attempts: 3           # Default: 3
      initial-delay-ms: 1000    # Default: 1s
      multiplier: 2.0           # Default: 2x
      max-delay-ms: 10000       # Default: 10s
```

- Retries on: rate limit (429), timeout, 5xx, connection errors
- No retry on: auth errors, invalid request, context too long
- Jitter (+-25%) prevents thundering herd
- Respects `CancellationException` for structured concurrency

```kotlin
// Custom transient error classifier
val executor = SpringAiAgentExecutor(
    chatClient = chatClient,
    properties = properties,
    transientErrorClassifier = { e ->
        e.message?.contains("429") == true
    }
)
```

### Parallel Tool Execution

When the LLM requests multiple tool calls in a single response, they execute concurrently:

```
Sequential (before):  Tool A (2s) → Tool B (2s) → Tool C (2s) = 6s
Parallel (now):       Tool A (2s) ┐
                      Tool B (2s) ├ = 2s
                      Tool C (2s) ┘
```

- Powered by `coroutineScope { map { async {} }.awaitAll() }`
- Result order preserved (matches tool call order)
- Hook lifecycle (BeforeToolCall/AfterToolCall) runs per tool
- One tool failure doesn't cancel others

### Structured Output

Request JSON responses from the LLM:

```kotlin
val result = agentExecutor.execute(
    AgentCommand(
        systemPrompt = "You are a data extraction agent.",
        userPrompt = "Extract the company info from: Arc Reactor Inc, founded 2024",
        responseFormat = ResponseFormat.JSON,
        responseSchema = """
            {
                "name": "string",
                "founded": "number"
            }
        """
    )
)
// result.content = {"name": "Arc Reactor Inc", "founded": 2024}
```

- Provider-independent (works via system prompt injection, not API-specific JSON mode)
- Optional `responseSchema` guides the output structure
- Not supported in streaming mode (partial JSON has no utility)

### MCP (Model Context Protocol)

Connect external tools dynamically via MCP:

```kotlin
@Service
class McpSetup(private val mcpManager: McpManager) {

    @PostConstruct
    fun setupMcpServers() {
        // Register filesystem MCP server
        mcpManager.register(McpServer(
            name = "filesystem",
            transportType = McpTransportType.STDIO,
            config = mapOf(
                "command" to "npx",
                "args" to listOf("-y", "@modelcontextprotocol/server-filesystem", "/data")
            )
        ))

        // Connect
        runBlocking {
            mcpManager.connect("filesystem")
        }
    }
}
```

### RAG Pipeline

Retrieval-Augmented Generation with pluggable components:

```yaml
arc:
  reactor:
    rag:
      enabled: true
      similarity-threshold: 0.7
      top-k: 10
```

```kotlin
// Custom retriever with Spring AI VectorStore
@Bean
fun documentRetriever(vectorStore: VectorStore): DocumentRetriever {
    return SpringAiVectorStoreRetriever(
        vectorStore = vectorStore,
        defaultSimilarityThreshold = 0.7
    )
}
```

### Memory

Session-based conversation memory with pluggable backends:

**In-Memory** (default, no config needed):

```kotlin
val result = agentExecutor.execute(
    AgentCommand(
        systemPrompt = "You are a helpful assistant.",
        userPrompt = "What did I ask before?",
        metadata = mapOf("sessionId" to "user-123-session-456")
    )
)
```

**PostgreSQL** (auto-detected when DataSource is available):

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    // Flyway migration creates the table automatically
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
}
```

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/arcreactor
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

No code changes needed. When a `DataSource` bean exists, `JdbcMemoryStore` is auto-configured instead of `InMemoryMemoryStore`.

## Configuration Reference

```yaml
arc:
  reactor:
    # LLM Settings
    llm:
      temperature: 0.3
      max-output-tokens: 4096
      max-conversation-turns: 10
      max-context-window-tokens: 128000    # Context window management

    # Retry Settings
    retry:
      max-attempts: 3
      initial-delay-ms: 1000
      multiplier: 2.0
      max-delay-ms: 10000

    # Guard Settings
    guard:
      enabled: true
      rate-limit-per-minute: 10
      rate-limit-per-hour: 100
      max-input-length: 10000
      injection-detection-enabled: true

    # RAG Settings
    rag:
      enabled: false
      similarity-threshold: 0.7
      top-k: 10
      rerank-enabled: true
      max-context-tokens: 4000

    # Concurrency Settings
    concurrency:
      max-concurrent-requests: 20
      request-timeout-ms: 30000

    # Tool Settings
    max-tools-per-request: 20
    max-tool-calls: 10
```

## Project Structure

```
arc-reactor/
├── agent/              # Agent Core
│   ├── AgentExecutor.kt
│   ├── config/AgentProperties.kt
│   ├── model/AgentModels.kt
│   └── impl/SpringAiAgentExecutor.kt
├── tool/               # Tool System
│   ├── LocalTool.kt
│   ├── ToolSelector.kt
│   ├── ToolCallback.kt
│   └── example/        # Example tools (not auto-registered)
├── hook/               # Lifecycle Hooks
│   ├── Hooks.kt (4 interfaces)
│   ├── HookExecutor.kt
│   └── model/HookModels.kt
├── guard/              # 5-Stage Guardrail
│   ├── Guard.kt
│   ├── model/GuardModels.kt
│   └── impl/
│       ├── GuardPipeline.kt
│       └── Default*Stage.kt
├── rag/                # RAG Pipeline
│   ├── RagPipeline.kt
│   ├── model/RagModels.kt
│   └── impl/
│       ├── DefaultRagPipeline.kt
│       ├── SpringAiVectorStoreRetriever.kt
│       └── *Reranker.kt
├── memory/             # Conversation Memory
│   ├── ConversationMemory.kt
│   └── JdbcMemoryStore.kt
├── mcp/                # MCP Support
│   ├── McpManager.kt
│   └── model/McpModels.kt
└── autoconfigure/      # Spring Boot Auto Config
    └── ArcReactorAutoConfiguration.kt
```

## Requirements

- Java 21+
- Spring Boot 3.5.9+
- Spring AI 1.1.2+
- Kotlin 2.3.0+

## Examples

See the [Quick Start](#quick-start) section above for a step-by-step integration guide.

The `tool/example/` directory contains reference implementations (`CalculatorTool`, `DateTimeTool`) showing how to build custom tools. These are not auto-registered; copy and add `@Component` to use them in your project.

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](./CONTRIBUTING.md) for guidelines.

## License

Apache License 2.0 - see [LICENSE](./LICENSE) for details.

## Acknowledgments

- Built on [Spring AI](https://spring.io/projects/spring-ai)
- Inspired by [LangChain](https://langchain.com) and [Claude Code](https://claude.ai)
- MCP integration based on [Model Context Protocol](https://modelcontextprotocol.io)
