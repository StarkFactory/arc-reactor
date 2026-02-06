# Arc Reactor

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-purple.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-orange.svg)](https://spring.io/projects/spring-ai)

**Lightweight AI Agent Framework for Spring Boot**

Arc Reactor is a production-ready AI Agent framework built on Spring AI. It provides a complete toolkit for building autonomous AI agents with guardrails, tools, memory, and RAG capabilities.

## Features

- **ReAct Pattern** - Autonomous Thought → Action → Observation execution loop
- **5-Stage Guard** - Rate Limit → Input Validation → Injection Detection → Classification → Permission
- **Dynamic Tools** - Local Tools with `@Tool` annotation + MCP (Model Context Protocol) support
- **Hook System** - 4 lifecycle extension points for logging, audit, and customization
- **RAG Pipeline** - Query Transform → Retrieve → Rerank → Context Build
- **Memory** - Multi-turn conversation context management
- **Spring Boot Auto-Configuration** - Zero-config setup with sensible defaults

## Quick Start

### 1. Add Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.arc:arc-reactor:0.2.0")

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
│  │ 1. Load Memory (conversation history)                        ││
│  │ 2. Select Tools (LocalTool + MCP)                           ││
│  │ 3. Call LLM with tools                                       ││
│  │ 4. Execute tool calls (with BeforeToolCall/AfterToolCall)   ││
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

```kotlin
// Enable RAG in configuration
arc:
  reactor:
    rag:
      enabled: true
      similarity-threshold: 0.7
      top-k: 10

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

Session-based conversation memory:

```kotlin
// Memory is automatically injected into AgentExecutor
// Use sessionId in metadata to track conversations

val result = agentExecutor.execute(
    AgentCommand(
        systemPrompt = "You are a helpful assistant.",
        userPrompt = "What did I ask before?",
        metadata = mapOf("sessionId" to "user-123-session-456")
    )
)
```

## Configuration Reference

```yaml
arc:
  reactor:
    # LLM Settings
    llm:
      temperature: 0.3
      max-output-tokens: 4096
      timeout-ms: 60000
      max-conversation-turns: 10

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
      request-timeout-seconds: 30

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
│   └── ToolCategory.kt
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
│   └── ConversationMemory.kt
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

See the [Quick Start](#quick-start) section above for a step-by-step integration guide. More examples including chatbot, customer service agent, and multi-agent setups will be added in future releases.

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](./CONTRIBUTING.md) for guidelines.

## License

Apache License 2.0 - see [LICENSE](./LICENSE) for details.

## Acknowledgments

- Built on [Spring AI](https://spring.io/projects/spring-ai)
- Inspired by [LangChain](https://langchain.com) and [Claude Code](https://claude.ai)
- MCP integration based on [Model Context Protocol](https://modelcontextprotocol.io)
