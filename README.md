# Arc Reactor

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-purple.svg)](https://kotlinlang.org)
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

Set provider keys with environment variables:

```bash
# Default provider (already enabled in arc-core): Google Gemini
export GEMINI_API_KEY=your-api-key

# Optional providers (when switched to implementation dependency)
# export SPRING_AI_OPENAI_API_KEY=your-api-key
# export SPRING_AI_ANTHROPIC_API_KEY=your-api-key
```

Provider dependencies are defined in `arc-core/build.gradle.kts`.

- Default: `spring-ai-starter-model-google-genai` is enabled.
- OpenAI/Anthropic are `compileOnly` by default.
- If you switch provider, change the target dependency to `implementation(...)` in `arc-core/build.gradle.kts`.

### 3. Create Tools

Add your business logic as tools in `arc-core/src/main/kotlin/com/arc/reactor/tool/`:

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

1. Open **Gradle Tool Window** and run `arc-app > Tasks > application > bootRun`
2. In **Run/Debug Configuration > Environment variables**, add:
   ```
   GEMINI_API_KEY=AIzaSy_your_actual_key
   ```
3. Run

> To also use PostgreSQL: `GEMINI_API_KEY=your_key;SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor;SPRING_DATASOURCE_USERNAME=arc;SPRING_DATASOURCE_PASSWORD=arc`

#### Run from CLI

```bash
export GEMINI_API_KEY=your-api-key
./gradlew :arc-app:bootRun
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
| **Response Caching** | Caffeine-based response cache (opt-in) | `cache.enabled`, temperature-based eligibility |
| **Circuit Breaker** | Kotlin-native circuit breaker (opt-in) | `circuit-breaker.enabled`, failure threshold |
| **Graceful Degradation** | Sequential model fallback on failure (opt-in) | `fallback.enabled`, fallback model list |
| **Response Filters** | Post-processing pipeline (e.g., max length) | Add `ResponseFilter` via `@Component` |
| **Observability Metrics** | 9 metric points across the pipeline | Replace `AgentMetrics` with Micrometer etc. |
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

> See the [Multi-Agent Guide](docs/en/architecture/multi-agent.md) for details.

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
| `arc-core/src/main/kotlin/com/arc/reactor/tool/` | **Add tools** - Connect business logic with `LocalTool` + `@Tool` annotation |
| `arc-core/src/main/resources/application.yml` (or external env/config) | **Change settings** - LLM provider, Guard thresholds, RAG on/off, etc. |
| `arc-core/src/main/kotlin/com/arc/reactor/guard/impl/` | **Custom Guards** - Implement classification/permission stages for your rules |
| `arc-core/src/main/kotlin/com/arc/reactor/hook/` | **Custom Hooks** - Add audit logging, billing, notifications via `@Component` |
| `arc-web/src/main/kotlin/com/arc/reactor/controller/` | **Modify API** - Add authentication, change endpoints, etc. |

### Leave As-Is (Already Structured)

| File/Package | Role |
|--------------|------|
| `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt` | ReAct loop, retry, context management - use as-is |
| `arc-core/src/main/kotlin/com/arc/reactor/guard/impl/GuardPipeline.kt` | Guard pipeline orchestration - use as-is |
| `arc-core/src/main/kotlin/com/arc/reactor/hook/HookExecutor.kt` | Hook execution engine - use as-is |
| `arc-core/src/main/kotlin/com/arc/reactor/memory/` | Conversation history management - auto-selects InMemory/JDBC |
| `arc-core/src/main/kotlin/com/arc/reactor/rag/impl/` | RAG pipeline - controlled via config |
| `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/` | Spring Boot auto-configuration - use as-is |

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

Use `-Pdb=true` when building/running and provide datasource settings (env vars or `application.yml`).
No code changes are needed - when a `DataSource` bean is detected, Arc Reactor switches to `JdbcMemoryStore`.

### Connect an MCP Server

Register MCP servers via REST API:

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "filesystem",
    "description": "Local filesystem tools",
    "transportType": "STDIO",
    "config": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
    },
    "autoConnect": true
  }'
```

> **Note:** MCP SDK 0.17.2 does not support Streamable HTTP transport. Use SSE for remote servers. See the [MCP Integration Guide](docs/en/architecture/mcp.md) for details.

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

    cache:                           # Response caching (opt-in)
      enabled: false
      max-size: 1000
      ttl-minutes: 60
      cacheable-temperature: 0.0     # Only cache when temperature <= this value

    circuit-breaker:                 # Circuit breaker (opt-in)
      enabled: false
      failure-threshold: 5
      reset-timeout-ms: 30000
      half-open-max-calls: 1

    fallback:                        # Graceful degradation (opt-in)
      enabled: false
      models: []                     # e.g., [openai, anthropic]

    auth:
      enabled: false                 # JWT authentication (opt-in)
      jwt-secret: ""                 # HMAC secret (required when enabled)
      jwt-expiration-ms: 86400000    # Token lifetime (24h)
```

## Project Structure

Arc Reactor is a multi-module Gradle project:

- `arc-app/`: executable assembly module (`:arc-app:bootRun`, `:arc-app:bootJar`)
- `arc-core/`: agent engine/library (guard, hook, tool, memory, RAG, MCP, policies)
- `arc-web/`: REST API controllers and web integration
- `arc-slack/`: Slack gateway
- `arc-discord/`: Discord gateway
- `arc-line/`: LINE gateway
- `arc-error-report/`: error reporting extension

Key implementation entrypoints:

- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorAutoConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/agent/config/AgentProperties.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/ChatController.kt`

## Documentation

- **[Docs Home](docs/en/README.md)** - Package-style documentation index
- [Module Layout Guide](docs/en/architecture/module-layout.md) - Current Gradle modules and runtime assembly
- [Testing and Performance Guide](docs/en/engineering/testing-and-performance.md) - Fast test feedback strategy
- [Slack Ops Runbook](docs/en/integrations/slack/ops-runbook.md) - Metrics, load testing, backpressure ops
- **[Tutorial: Build a Chatbot in 30 Minutes](docs/en/getting-started/tutorial-chatbot.md)** - End-to-end guide with custom tools, persona, and deployment
- [Architecture Guide](docs/en/architecture/architecture.md) - Internal structure and error handling
- [ReAct Loop Internals](docs/en/architecture/react-loop.md) - Core execution engine, parallel tool execution, context trimming, retry
- [Guard & Hook System](docs/en/architecture/guard-hook.md) - 5-stage security pipeline, 4 lifecycle extension points
- [Memory & RAG Pipeline](docs/en/architecture/memory-rag.md) - Conversation history, 4-stage retrieval-augmented generation
- [Tool Guide](docs/en/reference/tools.md) - 3 tool types, registration, MCP connection
- [MCP Integration Guide](docs/en/architecture/mcp.md) - McpManager, STDIO/SSE transports, dynamic tool loading
- [Configuration Reference](docs/en/getting-started/configuration.md) - Full YAML settings, auto-configuration, production examples
- [Observability & Metrics](docs/en/reference/metrics.md) - AgentMetrics interface, Micrometer integration, metric points
- [Resilience Guide](docs/en/architecture/resilience.md) - Circuit breaker, retry, graceful degradation
- [Response Processing](docs/en/architecture/response-processing.md) - Response filters, caching, structured output
- [Data Models & API](docs/en/reference/api-models.md) - AgentCommand/Result, error handling, metrics, REST API
- [Multi-Agent Guide](docs/en/architecture/multi-agent.md) - Sequential / Parallel / Supervisor patterns
- [Supervisor Pattern Deep Dive](docs/en/architecture/supervisor-pattern.md) - WorkerAgentTool internals, practical usage
- [Deployment Guide](docs/en/getting-started/deployment.md) - Docker, environment variables, production checklist
- [Authentication Guide](docs/en/governance/authentication.md) - JWT auth, AuthProvider customization, session isolation
- [Session & Persona Guide](docs/en/architecture/session-management.md) - Session API, persona management, data architecture
- [Prompt Versioning Guide](docs/en/governance/prompt-versioning.md) - Version control for system prompts, deployment, rollback
- [Feature Inventory](docs/en/reference/feature-inventory.md) - Complete feature matrix, data architecture, DB schema
- [Troubleshooting](docs/en/getting-started/troubleshooting.md) - Common issues and solutions

## Requirements

- Java 21+
- Spring Boot 3.5+
- Spring AI 1.1+
- Kotlin 2.3+

## License

Apache License 2.0 - See [LICENSE](LICENSE)

## Acknowledgments

- Built on [Spring AI](https://spring.io/projects/spring-ai)
- Integrates [Model Context Protocol](https://modelcontextprotocol.io)
