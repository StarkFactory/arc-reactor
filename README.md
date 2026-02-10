# Arc Reactor

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-purple.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-orange.svg)](https://spring.io/projects/spring-ai)

[한국어](README.ko.md)

**An open-source AI Agent project you can fork and use right away.**

Arc Reactor is an AI Agent project built on Spring AI. Production-ready patterns like Guard, Hook, Memory, RAG, MCP, and ReAct loops are already structured for you. Fork it, attach your tools, and deploy.

> **This is not a library or framework.** You don't add it via `implementation(...)`. Instead, fork it and make it your own project.

## Getting Started

### 1. Fork & Clone

```bash
# Fork on GitHub, then
git clone https://github.com/<your-username>/arc-reactor.git
cd arc-reactor
```

### 2. Configure LLM Provider

Set your LLM API key in `application.yml`:

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
    # Or Anthropic, Google Gemini, Vertex AI, etc.
```

Uncomment the provider you want to use in `build.gradle.kts`:

```kotlin
// Default: Google Gemini (already enabled)
implementation("org.springframework.ai:spring-ai-starter-model-google-genai")

// Enable only what you need
// compileOnly("org.springframework.ai:spring-ai-starter-model-openai")
// compileOnly("org.springframework.ai:spring-ai-starter-model-anthropic")
```

### 3. Create Tools

Add your business logic as tools in the `tool/` package:

```kotlin
@Component
class OrderTool : LocalTool {
    override val category = DefaultToolCategory.SEARCH

    @Tool(description = "Look up order status")
    fun getOrderStatus(@ToolParam("Order ID") orderId: String): String {
        return orderRepository.findById(orderId)?.status ?: "Order not found"
    }
}
```

### 4. Run

#### Run in IntelliJ

1. Click the **Run** button next to `main()` in `ArcReactorApplication.kt`
2. In **Run/Debug Configuration > Environment variables**, add:
   ```
   GEMINI_API_KEY=AIzaSy_your_actual_key
   ```
3. Run

> To also use PostgreSQL: `GEMINI_API_KEY=your_key;SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor;SPRING_DATASOURCE_USERNAME=arc;SPRING_DATASOURCE_PASSWORD=arc`

#### Run from CLI

```bash
export GEMINI_API_KEY=your-api-key
./gradlew bootRun
```

#### Run with Docker Compose

```bash
cp .env.example .env
# Edit .env and set GEMINI_API_KEY to your actual key

docker-compose up -d          # Start backend + PostgreSQL
docker-compose up app          # Start backend only (no PostgreSQL)
docker-compose down            # Stop
```

#### Test the API

```bash
# Regular response
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What is 3 + 5?"}'

# Streaming
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello"}'

# List available models
curl http://localhost:8080/api/models

# List sessions
curl http://localhost:8080/api/sessions
```

> **API Docs**: Open [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) for interactive API documentation.

> When auth is enabled, include `Authorization: Bearer <token>` in all requests. See [Authentication](#authentication-opt-in).

## What This Project Provides

| Feature | Description | Customization |
|---------|-------------|---------------|
| **ReAct Loop** | Autonomous Think -> Act -> Observe execution | Limit loops with `maxToolCalls` |
| **Guard (5 stages)** | RateLimit -> InputValidation -> InjectionDetection -> Classification -> Permission | Each stage can be replaced or extended |
| **Hook (4 lifecycle)** | Agent start/end, before/after tool call | Add hooks with `@Component` |
| **Memory** | Per-session conversation history (InMemory / PostgreSQL) | Replace with custom `MemoryStore` |
| **RAG** | QueryTransform -> Retrieve -> Rerank -> ContextBuild | Each of 4 stages is replaceable |
| **MCP** | Model Context Protocol (STDIO/SSE) | Connect external MCP servers |
| **Context Window Management** | Token-based message trimming | Configure token budget |
| **LLM Retry** | Exponential backoff with jitter | Configure retry conditions/attempts |
| **Parallel Tool Execution** | Coroutine-based concurrent execution | Automatic (no config needed) |
| **Structured Output** | JSON response mode | `responseFormat = JSON` |
| **Multi-Agent** | Sequential / Parallel / Supervisor | DSL builder API |
| **Authentication** | JWT auth with WebFilter (opt-in) | Replace `AuthProvider` / `UserStore` |
| **Persona Management** | Named system prompt templates (CRUD API) | Replace `PersonaStore` |
| **Session Management** | List / get / delete sessions via REST API | Auto-enabled |
| **Web UI** | React chat interface ([arc-reactor-web](https://github.com/eqprog/arc-reactor-web)) | Fork and customize |

## Multi-Agent

Supports 3 patterns where multiple specialized agents collaborate:

```kotlin
// Sequential: A's output -> B's input -> C's input
val result = MultiAgent.sequential()
    .node("researcher") { systemPrompt = "Research the topic" }
    .node("writer") { systemPrompt = "Write based on the research" }
    .execute(command, agentFactory)

// Parallel: Run simultaneously, merge results
val result = MultiAgent.parallel()
    .node("security") { systemPrompt = "Security analysis" }
    .node("style") { systemPrompt = "Style check" }
    .execute(command, agentFactory)

// Supervisor: Manager delegates tasks to workers
val result = MultiAgent.supervisor()
    .node("order") { systemPrompt = "Handle orders"; description = "Order lookup/modification" }
    .node("refund") { systemPrompt = "Handle refunds"; description = "Refund requests/status" }
    .execute(command, agentFactory)
```

> See the [Multi-Agent Guide](docs/en/multi-agent.md) for details.

## Architecture

```
User Request
     |
     v
+---------------------------------------------------+
|  GUARD PIPELINE                                    |
|  RateLimit -> InputValid -> InjDetect -> Classify -> Permission |
+---------------------------------------------------+
     |
     v
+---------------------------------------------------+
|  HOOK: BeforeAgentStart                            |
+---------------------------------------------------+
     |
     v
+---------------------------------------------------+
|  AGENT EXECUTOR (ReAct Loop)                       |
|  1. Load memory + context window trimming          |
|  2. Tool selection (Local + MCP)                   |
|  3. LLM call (with retry)                          |
|  4. Parallel tool execution (with hooks)           |
|  5. Return response or continue loop               |
+---------------------------------------------------+
     |
     v
+---------------------------------------------------+
|  HOOK: AfterAgentComplete                          |
+---------------------------------------------------+
     |
     v
  Response
```

## What to Modify

After forking, here's what you need to change vs. what you can leave as-is:

### Modify (Your Business Logic)

| File/Package | What to Do |
|--------------|------------|
| `tool/` | **Add tools** - Connect business logic with `LocalTool` + `@Tool` annotation |
| `application.yml` | **Change settings** - LLM provider, Guard thresholds, RAG on/off, etc. |
| `guard/impl/` | **Custom Guards** - Implement classification/permission stages for your rules |
| `hook/` | **Custom Hooks** - Add audit logging, billing, notifications via `@Component` |
| `controller/` | **Modify API** - Add authentication, change endpoints, etc. |

### Leave As-Is (Already Structured)

| File/Package | Role |
|--------------|------|
| `agent/impl/SpringAiAgentExecutor.kt` | ReAct loop, retry, context management - use as-is |
| `guard/impl/GuardPipeline.kt` | Guard pipeline orchestration - use as-is |
| `hook/HookExecutor.kt` | Hook execution engine - use as-is |
| `memory/` | Conversation history management - auto-selects InMemory/JDBC |
| `rag/impl/` | RAG pipeline - controlled via config |
| `autoconfigure/` | Spring Boot auto-configuration - use as-is |

## Customization Examples

### Add a Guard Stage

```kotlin
@Component
class BusinessHoursGuard : GuardStage {
    override val stageName = "business-hours"
    override val order = 35  // After InjectionDetection(30)

    override suspend fun check(command: GuardCommand): GuardResult {
        val hour = LocalTime.now().hour
        if (hour < 9 || hour >= 18) {
            return GuardResult.Rejected(
                reason = "Service is available only during business hours (09-18)",
                category = RejectionCategory.UNAUTHORIZED,
                stage = stageName
            )
        }
        return GuardResult.Allowed.DEFAULT
    }
}
```

### Add a Hook (Audit Logging)

```kotlin
@Component
class AuditHook : AfterAgentCompleteHook {
    override val order = 100

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        logger.info("User=${context.userId} prompt='${context.userPrompt}' " +
            "tools=${context.toolsUsed} success=${response.success}")
    }
}
```

### Custom Error Messages

```kotlin
@Bean
fun errorMessageResolver() = ErrorMessageResolver { code, _ ->
    when (code) {
        AgentErrorCode.RATE_LIMITED -> "Rate limit exceeded. Please try again later."
        AgentErrorCode.TIMEOUT -> "Request timed out."
        AgentErrorCode.GUARD_REJECTED -> "Request was rejected."
        else -> code.defaultMessage
    }
}
```

### Enable PostgreSQL Memory

Just uncomment the dependency in `build.gradle.kts` and add DB settings to `application.yml`. No code changes needed - when a `DataSource` bean is detected, it automatically switches to `JdbcMemoryStore`.

### Connect an MCP Server

```kotlin
@Service
class McpSetup(private val mcpManager: McpManager) {
    @PostConstruct
    fun setup() {
        mcpManager.register(McpServer(
            name = "filesystem",
            transportType = McpTransportType.STDIO,
            config = mapOf(
                "command" to "npx",
                "args" to listOf("-y", "@modelcontextprotocol/server-filesystem", "/data")
            )
        ))
        runBlocking { mcpManager.connect("filesystem") }
    }
}
```

> **Note:** MCP SDK 0.10.0 does not support the Streamable HTTP transport. Use SSE as an alternative for remote servers. See the [MCP Integration Guide](docs/en/mcp.md) for details.

## Authentication (Opt-in)

Arc Reactor includes a built-in JWT authentication system. It's **disabled by default** — enable it only when you need per-user session isolation.

```yaml
arc:
  reactor:
    auth:
      enabled: true                    # Enable JWT authentication
      jwt-secret: ${JWT_SECRET}        # HMAC signing secret (required)
      jwt-expiration-ms: 86400000      # Token lifetime (default: 24h)
```

When enabled:
- `POST /api/auth/register` — Create account
- `POST /api/auth/login` — Get JWT token
- `GET /api/auth/me` — Get current user profile
- All other endpoints require `Authorization: Bearer <token>`
- Sessions are automatically isolated per user

To use a custom identity provider (LDAP, SSO, etc.), implement the `AuthProvider` interface:

```kotlin
@Bean
fun authProvider(): AuthProvider = MyLdapAuthProvider()
```

## Configuration Reference

```yaml
arc:
  reactor:
    max-tools-per-request: 20    # Max tools per request
    max-tool-calls: 10           # Max tool calls per ReAct loop

    llm:
      temperature: 0.3
      max-output-tokens: 4096
      max-conversation-turns: 10
      max-context-window-tokens: 128000

    retry:
      max-attempts: 3
      initial-delay-ms: 1000
      multiplier: 2.0
      max-delay-ms: 10000

    guard:
      enabled: true
      rate-limit-per-minute: 10
      rate-limit-per-hour: 100
      max-input-length: 10000
      injection-detection-enabled: true

    rag:
      enabled: false
      similarity-threshold: 0.7
      top-k: 10
      rerank-enabled: true
      max-context-tokens: 4000

    concurrency:
      max-concurrent-requests: 20
      request-timeout-ms: 30000

    auth:
      enabled: false                 # JWT authentication (opt-in)
      jwt-secret: ""                 # HMAC secret (required when enabled)
      jwt-expiration-ms: 86400000    # Token lifetime (24h)
```

## Project Structure

```
src/main/kotlin/com/arc/reactor/
├── agent/                          # Agent core
│   ├── AgentExecutor.kt              -> Interface
│   ├── config/AgentProperties.kt     -> Settings (arc.reactor.*)
│   ├── model/AgentModels.kt          -> AgentCommand, AgentResult
│   ├── impl/SpringAiAgentExecutor.kt -> ReAct loop implementation
│   └── multi/                        -> Multi-agent (Sequential/Parallel/Supervisor)
│
├── guard/                          # 5-stage Guard
│   ├── Guard.kt                      -> GuardStage interfaces
│   ├── model/GuardModels.kt          -> GuardCommand, GuardResult
│   └── impl/                         -> Default implementations
│
├── hook/                           # Lifecycle Hooks
│   ├── Hook.kt                       -> 4 Hook interfaces
│   ├── HookExecutor.kt               -> Hook execution engine
│   └── model/HookModels.kt           -> HookContext, HookResult
│
├── tool/                           # Tool system <- Add your tools here
│   ├── ToolCallback.kt               -> Tool abstraction
│   ├── ToolSelector.kt               -> Tool selection strategy
│   ├── LocalTool.kt                  -> @Tool annotation-based tools
│   └── example/                      -> Examples (CalculatorTool, DateTimeTool)
│
├── memory/                         # Conversation Memory
│   ├── ConversationMemory.kt         -> Interface
│   ├── ConversationManager.kt        -> Conversation history lifecycle
│   ├── MemoryStore.kt                -> InMemory implementation
│   └── JdbcMemoryStore.kt            -> PostgreSQL implementation
│
├── rag/                            # RAG Pipeline
│   ├── RagPipeline.kt                -> 4-stage interface
│   └── impl/                         -> Default implementations
│
├── mcp/                            # MCP Protocol
│   ├── McpManager.kt                 -> MCP server management
│   └── model/McpModels.kt            -> McpServer, McpStatus
│
├── auth/                          # JWT Authentication (opt-in)
│   ├── AuthModels.kt                -> User, AuthProperties
│   ├── AuthProvider.kt              -> Interface (replaceable)
│   ├── DefaultAuthProvider.kt       -> BCrypt-based default implementation
│   ├── UserStore.kt                 -> Interface + InMemoryUserStore
│   ├── JdbcUserStore.kt             -> PostgreSQL implementation
│   ├── JwtTokenProvider.kt          -> JWT token create/validate
│   └── JwtAuthWebFilter.kt         -> WebFilter (token verification)
│
├── persona/                       # Persona Management
│   ├── PersonaStore.kt              -> Interface + InMemoryPersonaStore
│   └── JdbcPersonaStore.kt         -> PostgreSQL implementation
│
├── autoconfigure/                  # Spring Boot Auto-configuration
│   ├── ArcReactorAutoConfiguration.kt
│   └── OpenApiConfiguration.kt      -> Swagger UI / OpenAPI spec
│
├── controller/                     # REST API <- Modify as needed
│   ├── ChatController.kt           -> POST /api/chat, /api/chat/stream
│   ├── SessionController.kt        -> GET/DELETE /api/sessions, GET /api/models
│   ├── AuthController.kt           -> POST /api/auth/register|login, GET /api/auth/me
│   ├── PersonaController.kt        -> CRUD /api/personas
│   └── PromptTemplateController.kt -> Versioned prompt management (ADMIN)
│
└── config/
    └── ChatClientConfig.kt
```

## Documentation

- [Architecture Guide](docs/en/architecture.md) - Internal structure and error handling
- [ReAct Loop Internals](docs/en/react-loop.md) - Core execution engine, parallel tool execution, context trimming, retry
- [Guard & Hook System](docs/en/guard-hook.md) - 5-stage security pipeline, 4 lifecycle extension points
- [Memory & RAG Pipeline](docs/en/memory-rag.md) - Conversation history, 4-stage retrieval-augmented generation
- [Tool Guide](docs/en/tools.md) - 3 tool types, registration, MCP connection
- [MCP Integration Guide](docs/en/mcp.md) - McpManager, STDIO/SSE transports, dynamic tool loading
- [Configuration Reference](docs/en/configuration.md) - Full YAML settings, auto-configuration, production examples
- [Data Models & API](docs/en/api-models.md) - AgentCommand/Result, error handling, metrics, REST API
- [Multi-Agent Guide](docs/en/multi-agent.md) - Sequential / Parallel / Supervisor patterns
- [Supervisor Pattern Deep Dive](docs/en/supervisor-pattern.md) - WorkerAgentTool internals, practical usage
- [Deployment Guide](docs/en/deployment.md) - Docker, environment variables, production checklist
- [Authentication Guide](docs/en/authentication.md) - JWT auth, AuthProvider customization, session isolation
- [Session & Persona Guide](docs/en/session-management.md) - Session API, persona management, data architecture
- [Prompt Versioning Guide](docs/en/prompt-versioning.md) - Version control for system prompts, deployment, rollback
- [Feature Inventory](docs/en/feature-inventory.md) - Complete feature matrix, data architecture, DB schema
- [Troubleshooting](docs/en/troubleshooting.md) - Common issues and solutions

## Requirements

- Java 21+
- Spring Boot 3.5+
- Spring AI 1.1+
- Kotlin 2.3+

## License

Apache License 2.0 - See [LICENSE](./LICENSE)

## Acknowledgments

- Built on [Spring AI](https://spring.io/projects/spring-ai)
- Integrates [Model Context Protocol](https://modelcontextprotocol.io)
