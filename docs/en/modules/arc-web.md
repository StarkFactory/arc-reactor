# arc-web

## Overview

`arc-web` provides the HTTP layer for Arc Reactor. It exposes REST endpoints for chat (standard and streaming SSE), session management, persona management, prompt template management, MCP server registration, RAG document management, and authentication. It also installs security headers, optional CORS, tenant context resolution, and global exception handling.

`arc-web` depends on `arc-core` at runtime. It is a separate module so that teams building non-HTTP integrations (Slack bots, gRPC services, CLI tools) can include `arc-core` without pulling in the web layer.

All controllers are registered as Spring beans and can be replaced with your own implementations via `@ConditionalOnMissingBean` or by disabling the auto-configuration.

---

## Key Components

| Class | Role | Package |
|---|---|---|
| `ChatController` | `POST /api/chat` and `POST /api/chat/stream` | `controller` |
| `MultipartChatController` | `POST /api/chat/multipart` — file upload + multimodal | `controller` |
| `SessionController` | Session list, detail, export, and delete; model list | `controller` |
| `PersonaController` | CRUD for named system prompt personas | `controller` |
| `PromptTemplateController` | Versioned prompt template management | `controller` |
| `McpServerController` | Dynamic MCP server registration and lifecycle | `controller` |
| `AuthController` | JWT register/login/me | `controller` |
| `DocumentController` | Vector store document add/search/delete (RAG) | `controller` |
| `FeedbackController` | Thumbs-up/down feedback capture | `controller` |
| `IntentController` | Intent registry CRUD | `controller` |
| `SchedulerController` | Dynamic cron job management | `controller` |
| `ToolPolicyController` | Runtime tool policy management | `controller` |
| `OutputGuardRuleController` | Dynamic output guard rule management | `controller` |
| `ApprovalController` | Human-in-the-Loop approval workflow | `controller` |
| `PromptLabController` | Prompt Lab experiment management | `controller` |
| `OpsDashboardController` | Operations dashboard (metrics, anomaly detection) | `controller` |
| `GlobalExceptionHandler` | `@RestControllerAdvice` — standardized error responses | `controller` |
| `SecurityHeadersWebFilter` | Adds security headers to every HTTP response | `autoconfigure` |
| `CorsSecurityConfiguration` | Optional CORS filter (opt-in) | `autoconfigure` |
| `ApiVersionContractWebFilter` | API version contract enforcement via `X-Api-Version` header | `autoconfigure` |
| `TenantContextResolver` | Resolves tenant ID from JWT attributes or `X-Tenant-Id` header | `controller` |
| `AdminAuthSupport` | Shared `isAdmin()` and `forbiddenResponse()` helpers | `controller` |
| `ArcReactorWebAutoConfiguration` | Web-layer auto-configuration entrypoint | `autoconfigure` |

---

## Configuration

Web-specific properties are part of the same `arc.reactor.*` namespace as `arc-core`.

### Security Headers (`arc.reactor.security-headers`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Adds standard security headers to all responses |

Headers applied when enabled:

| Header | Value |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Content-Security-Policy` | `default-src 'self'` |
| `X-XSS-Protection` | `0` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |

### CORS (`arc.reactor.cors`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | CORS filter (opt-in) |
| `allowed-origins` | `[http://localhost:3000]` | Allowed origins |
| `allowed-methods` | `[GET, POST, PUT, DELETE, OPTIONS]` | Allowed HTTP methods |
| `allowed-headers` | `[*]` | Allowed request headers |
| `allow-credentials` | `false` | Allow cookies/Authorization header |
| `max-age` | `3600` | Preflight cache in seconds |

### Auth (`arc.reactor.auth`)

| Property | Default | Description |
|---|---|---|
| `jwt-secret` | (empty) | JWT signing secret (minimum 32 bytes, required) |
| `default-tenant-id` | `default` | Tenant claim value issued in JWT tokens |
| `public-actuator-health` | `false` | Add `/actuator/health` to public paths |

`JwtAuthWebFilter` validates the `Authorization: Bearer <token>` header and sets user ID and role
attributes on the exchange. Runtime startup fails when `arc.reactor.auth.jwt-secret` is missing or short.

### Multimodal (`arc.reactor.multimodal`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Multipart file upload endpoint |
| `max-file-size-bytes` | `10485760` | 10 MB per file |
| `max-files-per-request` | `5` | Files per multipart request |

### API Version Contract

| Property | Default | Description |
|---|---|---|
| `arc.reactor.api-version.enabled` | `true` | API version contract enforcement |
| `arc.reactor.api-version.current` | `v1` | Current API version |
| `arc.reactor.api-version.supported` | (current) | Comma-separated supported versions |

---

## Extension Points

### Controller Overrides

Because all controllers are plain Spring `@RestController` beans registered via auto-configuration, you can replace any controller by declaring a bean with the same name or by excluding the auto-configuration class. The recommended approach is to extend the controller and re-register it:

```kotlin
@RestController
@RequestMapping("/api/chat")
@Primary
class MyCustomChatController(
    agentExecutor: AgentExecutor,
    properties: AgentProperties
) : ChatController(agentExecutor, properties = properties) {

    @PostMapping("/v2")
    suspend fun chatV2(@RequestBody request: MyChatRequest, exchange: ServerWebExchange): ChatResponse {
        // custom routing logic
    }
}
```

### WebFilter — Custom Request Filters

Register a `WebFilter` bean to intercept all requests before controllers:

```kotlin
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class ApiKeyWebFilter : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val apiKey = exchange.request.headers.getFirst("X-API-Key")
        if (apiKey != expectedKey) {
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }
        return chain.filter(exchange)
    }
}
```

`SecurityHeadersWebFilter` runs at `Ordered.HIGHEST_PRECEDENCE + 1`, so any filter at a higher order number executes after it.

### Tenant Resolution

The `TenantContextResolver` resolves the tenant ID in this priority order:

1. `resolvedTenantId` exchange attribute (set by JWT filter)
2. `tenantId` exchange attribute (legacy)
3. `X-Tenant-Id` request header (validated against `^[a-zA-Z0-9_-]{1,64}$`)
4. Default `"default"` (fallback)

When no tenant context is present, the request is rejected with HTTP 400.

### Admin Authorization

Controllers that perform write operations call `isAdmin(exchange)` from `AdminAuthSupport.kt`:

```kotlin
// AdminAuthSupport.kt
fun isAdmin(exchange: ServerWebExchange): Boolean {
    val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
    return role == UserRole.ADMIN
}
```

Missing role fails closed as non-admin.

Always use the shared `isAdmin()` function. Do not duplicate this logic in custom controllers.

### System Prompt Resolution

`ChatController` resolves the system prompt with this priority:

1. `personaId` → `PersonaStore` lookup
2. `promptTemplateId` → active `PromptVersion` content
3. `request.systemPrompt` → direct override
4. Default persona (`isDefault=true`) from `PersonaStore`
5. Hardcoded fallback: `"You are a helpful AI assistant."`

### Custom AuthProvider

Replace the default JWT + database authentication:

```kotlin
@Bean
@Primary
fun authProvider(): AuthProvider = MyLdapAuthProvider()
```

Implement `AuthProvider` (in `arc-core`):

```kotlin
interface AuthProvider {
    fun authenticate(email: String, password: String): User?
    fun getUserById(id: String): User?
}
```

---

## API Reference

### Chat

| Method | Path | Description | Auth Required |
|---|---|---|---|
| `POST` | `/api/chat` | Standard chat (full response) | No |
| `POST` | `/api/chat/stream` | Streaming chat (SSE) | No |
| `POST` | `/api/chat/multipart` | Multipart chat with file attachments | No |

**ChatRequest fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `message` | `String` | Yes | User input (max 50,000 chars) |
| `model` | `String` | No | Override default LLM provider |
| `systemPrompt` | `String` | No | Direct system prompt (max 10,000 chars) |
| `personaId` | `String` | No | Persona to use for system prompt |
| `promptTemplateId` | `String` | No | Template to use for system prompt |
| `userId` | `String` | No | User identifier |
| `metadata` | `Map<String, Any>` | No | Pass `sessionId`, `channel`, etc. (max 20 entries) |
| `responseFormat` | `TEXT\|JSON\|YAML` | No | Structured output format |
| `responseSchema` | `String` | No | JSON Schema for structured output |
| `mediaUrls` | `List<MediaUrlRequest>` | No | Media attachments by URL |

**SSE event types** (streaming endpoint):

| Event | Data | Description |
|---|---|---|
| `message` | text chunk | LLM token |
| `tool_start` | tool name | Tool execution started |
| `tool_end` | tool name | Tool execution completed |
| `error` | error message | Error occurred |
| `done` | (empty) | Stream complete |

### Sessions

| Method | Path | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/sessions` | List all sessions | No (filtered by user when auth enabled) |
| `GET` | `/api/sessions/{sessionId}` | Get session messages | No (owner check when auth enabled) |
| `GET` | `/api/sessions/{sessionId}/export?format=json` | Export as JSON | No |
| `GET` | `/api/sessions/{sessionId}/export?format=markdown` | Export as Markdown | No |
| `DELETE` | `/api/sessions/{sessionId}` | Delete session | No (owner check when auth enabled) |
| `GET` | `/api/models` | List available LLM providers | No |

### Personas

| Method | Path | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/personas` | List all personas | No |
| `GET` | `/api/personas/{personaId}` | Get persona by ID | No |
| `POST` | `/api/personas` | Create persona | Admin |
| `PUT` | `/api/personas/{personaId}` | Update persona | Admin |
| `DELETE` | `/api/personas/{personaId}` | Delete persona | Admin |

### Prompt Templates

| Method | Path | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/prompt-templates` | List templates | No |
| `GET` | `/api/prompt-templates/{id}` | Get template with versions | No |
| `POST` | `/api/prompt-templates` | Create template | Admin |
| `PUT` | `/api/prompt-templates/{id}` | Update template metadata | Admin |
| `DELETE` | `/api/prompt-templates/{id}` | Delete template | Admin |
| `POST` | `/api/prompt-templates/{id}/versions` | Create version | Admin |
| `PUT` | `/api/prompt-templates/{id}/versions/{vid}/activate` | Activate version | Admin |
| `PUT` | `/api/prompt-templates/{id}/versions/{vid}/archive` | Archive version | Admin |

### MCP Servers

| Method | Path | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/mcp/servers` | List all servers with status | No |
| `POST` | `/api/mcp/servers` | Register server | Admin |
| `GET` | `/api/mcp/servers/{name}` | Server details with tools | No |
| `PUT` | `/api/mcp/servers/{name}` | Update server config | Admin |
| `DELETE` | `/api/mcp/servers/{name}` | Disconnect and remove | Admin |
| `POST` | `/api/mcp/servers/{name}/connect` | Connect to server | Admin |
| `POST` | `/api/mcp/servers/{name}/disconnect` | Disconnect from server | Admin |

### Authentication

| Method | Path | Description | Auth Required |
|---|---|---|---|
| `POST` | `/api/auth/register` | Register new user | No |
| `POST` | `/api/auth/login` | Login, receive JWT | No |
| `GET` | `/api/auth/me` | Get current user profile | JWT |
| `POST` | `/api/auth/change-password` | Change password | JWT |

### Documents (requires `arc.reactor.rag.enabled=true`)

| Method | Path | Description | Auth Required |
|---|---|---|---|
| `POST` | `/api/documents` | Add document to vector store | Admin |
| `POST` | `/api/documents/batch` | Batch add documents | Admin |
| `POST` | `/api/documents/search` | Similarity search | No |
| `DELETE` | `/api/documents` | Delete documents by IDs | Admin |

---

## Code Examples

### Standard Chat Call

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "What is the weather in Seoul?",
    "metadata": { "sessionId": "session-123", "channel": "web" }
  }'
```

Response:

```json
{
  "content": "The current weather in Seoul is...",
  "success": true,
  "toolsUsed": ["get_weather"],
  "errorMessage": null
}
```

### Streaming Chat (SSE)

```bash
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Explain quantum computing"}' \
  --no-buffer
```

Kotlin client example:

```kotlin
val client = WebClient.create("http://localhost:8080")

client.post()
    .uri("/api/chat/stream")
    .bodyValue(ChatRequest(message = "Explain quantum computing"))
    .retrieve()
    .bodyToFlux(ServerSentEvent::class.java)
    .subscribe { event ->
        when (event.event()) {
            "message" -> print(event.data())
            "tool_start" -> println("\n[Tool: ${event.data()}]")
            "done" -> println("\n[Done]")
        }
    }
```

### File Upload (Multimodal)

```bash
curl -X POST http://localhost:8080/api/chat/multipart \
  -F "message=What's in this image?" \
  -F "files=@photo.png"
```

### Register MCP Server

```bash
# SSE transport
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-mcp-server",
    "transportType": "SSE",
    "config": { "url": "http://localhost:8081/sse" },
    "autoConnect": true
  }'

# STDIO transport
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "fs-server",
    "transportType": "STDIO",
    "config": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"] }
  }'
```

---

## Common Pitfalls

**All 403 responses must include an `ErrorResponse` body.** Never return `ResponseEntity.status(403).build()` — always use `forbiddenResponse()` or construct the body explicitly. Empty 403 bodies break API clients that expect consistent error structure.

**Do not duplicate `isAdmin()` logic.** Use only the shared `isAdmin(exchange)` function from `AdminAuthSupport.kt`. Duplicating this logic risks divergence and security gaps.

**`SessionController` uses its own `sessionForbidden()` function** with a session-specific error message. This is intentional and differs from the general `forbiddenResponse()`.

**Streaming endpoints do not support structured output formats.** Requesting `responseFormat = JSON` or `YAML` on `POST /api/chat/stream` returns an error chunk. Streaming is text-token-only.

**MCP HTTP transport is not supported.** Attempting to register an MCP server with `transportType: HTTP` returns HTTP 400. Use `SSE` or `STDIO`.

**Tenant ID format is strictly validated.** The `X-Tenant-Id` header must match `^[a-zA-Z0-9_-]{1,64}$`. Requests with invalid format are rejected with HTTP 400.

**Multipart file uploads are DoS-protected.** The `MultipartChatController` enforces per-file size limits during streaming (before bytes are fully loaded into memory). Do not bypass this by setting very high `max-file-size-bytes` without understanding the memory impact.

**Auth is mandatory.** Provide a valid JWT secret in every runtime environment.
