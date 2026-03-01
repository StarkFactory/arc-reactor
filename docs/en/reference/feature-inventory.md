# Arc Reactor Feature Inventory & Data Architecture

> Last updated: 2026-03-01

---

## 1. Full Feature Matrix

### 1.1 Backend (arc-reactor)

| Feature | API Endpoint | Status | Description |
|---------|-------------|--------|-------------|
| **Standard Chat** | `POST /api/chat` | Active | Returns entire response at once |
| **Streaming Chat** | `POST /api/chat/stream` | Active | SSE real-time token streaming |
| **Model Selection** | `ChatRequest.model` | Active | Runtime switching between gemini/openai/anthropic/vertex |
| **System Prompt** | `ChatRequest.systemPrompt` | Active | Per-request custom system prompt |
| **Response Format** | `ChatRequest.responseFormat` | Active | TEXT/JSON mode |
| **Conversation Memory** | `metadata.sessionId` → MemoryStore | Active | Automatic per-session conversation history save/load |
| **Guard Pipeline** | Internal (no API) | Active | Rate Limit → Input Validation → Injection Detection → Classification → Permission |
| **Hook System** | Internal (no API) | Active | BeforeAgentStart → BeforeToolCall → AfterToolCall → AfterAgentComplete |
| **Tool Execution** | Internal (no API) | Active | Local tools + MCP tool auto-discovery |
| **RAG Pipeline** | Internal (no API) | Inactive (configurable) | Auto-activates when VectorStore is connected. Supports HyDE, conversation-aware query rewriting, metadata filtering |
| **Multi-Agent** | `POST /api/multi/*` | **Inactive** | @RestController is commented out |
| **Session List** | `GET /api/sessions` | Active | Session summary list (messageCount, lastActivity, preview) |
| **Conversation History** | `GET /api/sessions/{id}` | Active | Full message history for a specific session |
| **Session Deletion** | `DELETE /api/sessions/{id}` | Active | Server-side session data deletion |
| **Available Models** | `GET /api/models` | Active | Registered LLM provider list + default model |
| **JWT Authentication** | `POST /api/auth/*` | **required** | Runtime requires `arc.reactor.auth.jwt-secret` |
| **Persona Management** | `GET/POST/PUT/DELETE /api/personas` | Active | System prompt template CRUD |
| **Per-User Session Isolation** | Internal (no API) | Active | Sessions are filtered by JWT-derived userId (auth is always required) |
| **Response Caching** | Internal (no API) | **opt-in** | Caffeine-based cache, SHA-256 keys, temperature-based eligibility |
| **Circuit Breaker** | Internal (no API) | **opt-in** | Kotlin-native CB: CLOSED → OPEN → HALF_OPEN state machine |
| **Graceful Degradation** | Internal (no API) | **opt-in** | Sequential model fallback on primary model failure |
| **Response Filters** | Internal (no API) | Active | Post-processing pipeline (MaxLengthResponseFilter built-in) |
| **Streaming Error Events** | SSE `error` event | Active | Error events during streaming with StreamEventMarker |
| **Observability Metrics** | Internal (no API) | Active | 9 metric points: execution, tool, guard, cache, CB, fallback, tokens |
| **MCP Auto-Reconnection** | Internal (no API) | Active | Exponential backoff + jitter, per-server Mutex |

### 1.2 Frontend (arc-reactor-web)

| Feature | Status | Data Storage | Server Integration |
|---------|--------|-------------|-------------------|
| **Multi-Session Management** | Implemented | localStorage | sessionId sent via metadata |
| **Conversation History** | Implemented | localStorage | Not loaded from server |
| **Model Selection** | Implemented | localStorage | Sent via `model` field |
| **System Prompt** | Implemented | localStorage | Sent via `systemPrompt` field |
| **Response Format** | Implemented | localStorage | Sent via `responseFormat` field |
| **Dark/Light Mode** | Implemented | localStorage | Client-only |
| **Markdown Rendering** | Implemented | — | — |
| **Code Syntax Highlighting** | Implemented | — | — |
| **Message Copy** | Implemented | — | — |
| **Retry** | Implemented | — | — |
| **Tool Usage Display** | Implemented | — | SSE `tool_start`/`tool_end` events |
| **Response Time Display** | Implemented | — | Measured on frontend |
| **User Authentication** | Implemented | localStorage (token) | Login/signup UI shown automatically when backend auth is enabled |
| **Persona Selection** | Implemented | Reflected in settings | Persona list query + selection + inline CRUD |
| **Session Server Sync** | Implemented | localStorage + server | GET/DELETE /api/sessions integration |

---

## 2. Data Architecture

### 2.1 Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                           Browser                                │
│                                                                  │
│  localStorage                                                    │
│  ├── arc-reactor-sessions:{userId}  (Session[] — max 50)        │
│  │   ├── session.id          (UUID)                              │
│  │   ├── session.title       (first 30 chars of first message)   │
│  │   ├── session.messages[]  (full conversation history)         │
│  │   └── session.updatedAt   (timestamp)                         │
│  ├── arc-reactor-settings:{userId}  (ChatSettings)               │
│  │   ├── model, systemPrompt, responseFormat                     │
│  │   ├── darkMode, showMetadata                                  │
│  │   └── sidebarOpen                                             │
│  └── arc-reactor-auth-token  (JWT token, when auth is enabled)   │
│                                                                  │
│  POST /api/chat/stream ──────────────────────┐                   │
│  { message, userId, model, systemPrompt,     │                   │
│    responseFormat, metadata: { sessionId } }  │                   │
└───────────────────────────────────────────────┼──────────────────┘
                                                │
                                                ▼
┌───────────────────────────────────────────────────────────────────┐
│                      Backend (Spring Boot)                         │
│                                                                   │
│  Guard Pipeline ─→ Hook ─→ ConversationManager ─→ ReAct Loop      │
│                              │                                    │
│                              ▼                                    │
│                    ┌─────────────────────┐                        │
│                    │    MemoryStore      │                        │
│                    │  (per-session save) │                        │
│                    └────────┬────────────┘                        │
│                             │                                     │
│              ┌──────────────┼──────────────┐                      │
│              ▼                              ▼                     │
│   InMemoryMemoryStore              JdbcMemoryStore                │
│   (Caffeine cache)                 (PostgreSQL)                   │
│   - max 1000 sessions             - conversation_messages table   │
│   - 50 messages per session       - 100 messages per session      │
│   - lost on server restart        - persistent storage            │
│   - LRU auto-eviction             - TTL-based cleanup             │
└───────────────────────────────────────────────────────────────────┘
```

### 2.2 Dual Data Storage Architecture

Conversation data is currently stored **independently in two locations**:

| Storage | Location | Contents | Persistence | Purpose |
|---------|----------|----------|-------------|---------|
| **localStorage** | Browser | Session list + full messages | Lost when browser data is cleared | UI display, session switching |
| **MemoryStore** | Server memory or DB | Per-session messages (role + content) | Depends on configuration | LLM conversation context |

**Key differences:**
- localStorage messages: `id`, `role`, `content`, `toolsUsed`, `error`, `timestamp`, `durationMs` (rich metadata)
- MemoryStore messages: `role`, `content`, `timestamp` (minimal information needed for LLM context)

The two stores are **not synchronized.** Each stores and reads independently.

---

## 3. Session Architecture

### 3.1 Session Lifecycle

```
[User clicks "New Chat"]
    │
    ▼
Browser: Session { id: UUID, title: "New Chat", messages: [] }
    → saved to localStorage
    │
    ▼
[User enters a message]
    │
    ├─→ Browser: add message to session.messages[] → save to localStorage
    │
    └─→ Server: POST /api/chat/stream { metadata: { sessionId: UUID } }
             │
             ├─→ ConversationManager.loadHistory(sessionId)
             │     → load previous conversation from MemoryStore (if exists)
             │     → pass to LLM as context
             │
             ├─→ LLM execution (previous conversation context + current message)
             │
             └─→ ConversationManager.saveStreamingHistory(sessionId, content)
                   → save user + assistant messages to MemoryStore
```

### 3.2 Session ID Propagation Path

```
Frontend                           Backend
────────                           ───────
ChatContext.tsx                     ChatController.kt
  activeSessionId ─────────────→     request.metadata["sessionId"]
  (UUID, localStorage)                    │
                                          ▼
                                   ConversationManager.kt
                                     command.metadata["sessionId"]
                                          │
                                          ▼
                                   MemoryStore.getOrCreate(sessionId)
```

### 3.3 Session Persistence Comparison

| Scenario | localStorage (Browser) | InMemoryMemoryStore (Server) | JdbcMemoryStore (DB) |
|----------|----------------------|--------------------------|---------------------|
| Page refresh | **Retained** | **Retained** | **Retained** |
| Close browser tab | **Retained** | **Retained** | **Retained** |
| Clear browser data | **Lost** | **Retained** | **Retained** |
| Server restart | **Retained** | **Lost** | **Retained** |
| Docker redeployment | **Retained** | **Lost** | **Retained** if using Volume |
| Different browser/device | **Lost** | **Retained** (if sessionId is known) | **Retained** (if sessionId is known) |

**Deployment state:**
- Without PostgreSQL connection → `InMemoryMemoryStore` (lost on server restart)
- With PostgreSQL connection → `JdbcMemoryStore` (persistent storage)
- With authentication enabled → sessions auto-isolated by `userId`, localStorage also namespaced per userId

---

## 4. DB Schema (When PostgreSQL Is Connected)

### 4.1 conversation_messages Table

```sql
-- Flyway V1__create_conversation_messages.sql
CREATE TABLE conversation_messages (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(255)  NOT NULL,    -- session UUID
    role        VARCHAR(20)   NOT NULL,    -- user, assistant, system, tool
    content     TEXT          NOT NULL,    -- message body
    timestamp   BIGINT        NOT NULL,    -- epoch millis
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_conversation_messages_session_id
    ON conversation_messages (session_id);

CREATE INDEX idx_conversation_messages_session_timestamp
    ON conversation_messages (session_id, timestamp);
```

### 4.2 personas Table

```sql
-- Flyway V2__create_personas.sql
CREATE TABLE IF NOT EXISTS personas (
    id            VARCHAR(36)   PRIMARY KEY,
    name          VARCHAR(200)  NOT NULL,
    system_prompt TEXT          NOT NULL,
    is_default    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### 4.3 users Table

```sql
-- Flyway V3__create_users.sql
CREATE TABLE IF NOT EXISTS users (
    id            VARCHAR(36)   PRIMARY KEY,
    email         VARCHAR(255)  NOT NULL UNIQUE,
    name          VARCHAR(100)  NOT NULL,
    password_hash VARCHAR(255)  NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users (email);
```

### 4.4 Adding userId to conversation_messages

```sql
-- Flyway V4__add_user_id_to_conversation_messages.sql
ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(36) NOT NULL DEFAULT 'anonymous';

CREATE INDEX IF NOT EXISTS idx_conversation_messages_user_id
    ON conversation_messages (user_id);
CREATE INDEX IF NOT EXISTS idx_conversation_messages_user_session
    ON conversation_messages (user_id, session_id);
```

### 4.5 Data Examples

Data stored when PostgreSQL is connected:

| id | session_id | role | content | timestamp | created_at |
|----|-----------|------|---------|-----------|------------|
| 1 | a1b2c3d4-... | user | Hello | 1707350400000 | 2026-02-08 12:00:00 |
| 2 | a1b2c3d4-... | assistant | Hello! How can I help you? | 1707350402300 | 2026-02-08 12:00:02 |
| 3 | a1b2c3d4-... | user | What is Python? | 1707350410000 | 2026-02-08 12:00:10 |
| 4 | a1b2c3d4-... | assistant | Python is a programming language that... | 1707350413500 | 2026-02-08 12:00:13 |

### 4.3 MemoryStore Limits & Cleanup Policies

| Setting | InMemory (default) | JDBC (PostgreSQL) |
|---------|-------------------|-------------------|
| Max sessions | 1,000 (Caffeine LRU) | Unlimited (disk) |
| Max messages per session | 50 | 100 |
| Behavior on overflow | FIFO (oldest messages deleted) | FIFO (DELETE + LIMIT) |
| Session expiration | LRU auto-eviction | `cleanupExpiredSessions(ttlMs)` manual call |
| Token-limited load | `getHistoryWithinTokenLimit(maxTokens)` | Same |

---

## 5. Differences by Deployment Configuration

### 5.1 arc-reactor-web/docker-compose.yml (Currently in Use)

```yaml
services:
  backend:    # arc-reactor (only GEMINI_API_KEY configured)
  web:        # nginx + React build
# No PostgreSQL → InMemoryMemoryStore
```

- **Pros:** Simple, quick start
- **Cons:** Conversation context lost on server restart

### 5.2 arc-reactor/docker-compose.yml (Includes PostgreSQL)

```yaml
services:
  app:        # arc-reactor (built with -Pdb=true, includes DB connection config)
  db:         # PostgreSQL 16 Alpine
# JdbcMemoryStore + JdbcPersonaStore + JdbcUserStore auto-activated
```

- **Pros:** Permanent conversation history preservation, context persists after server restart
- **Cons:** Requires DB management

### 5.3 Deployment (authentication is always enabled)

```bash
# Enable db flag during Docker build
# In Dockerfile: ARG ENABLE_DB → -Pdb=true
docker compose up -d

# Environment variable example (.env)
GEMINI_API_KEY=your-key
ARC_REACTOR_AUTH_JWT_SECRET=your-256-bit-secret
```

With current runtime defaults, the following happens automatically:
- All APIs require a JWT token (except `/api/auth/login`, `/api/auth/register`)
- Sessions are isolated by userId (users cannot access other users' sessions)
- user_id column is automatically added to conversation_messages (Flyway V4)

### 5.4 How to Enable PostgreSQL

Add a DB service to arc-reactor-web's docker-compose, or set environment variables:

```bash
# Method 1: Connect to an external PostgreSQL
SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/arcreactor \
SPRING_DATASOURCE_USERNAME=arc \
SPRING_DATASOURCE_PASSWORD=arc \
docker compose up -d

# Method 2: Use arc-reactor/docker-compose.yml (includes DB)
cd arc-reactor && docker compose up -d
```

`JdbcMemoryStore` is **automatically activated** when a `DataSource` bean exists (`@ConditionalOnClass` + `@ConditionalOnBean`).

---

## 6. Full ChatRequest Field Specification

Request sent from the frontend to the backend:

```json
{
  "message": "User input text (required, NotBlank)",
  "model": "gemini | openai | anthropic | vertex | null (server default)",
  "systemPrompt": "Custom system prompt | null (default prompt)",
  "userId": "Optional client hint (server extracts authoritative userId from JWT)",
  "metadata": {
    "sessionId": "UUID (session identifier)"
  },
  "responseFormat": "TEXT | JSON | null (default: TEXT)",
  "responseSchema": "JSON schema string | null"
}
```

### Role of Each Field

| Field | Sent by Frontend | Backend Usage |
|-------|-----------------|--------------|
| `message` | **Always** | Passed to LLM as userPrompt |
| `model` | When configured | `ChatModelProvider` selects the ChatModel for the corresponding provider |
| `systemPrompt` | When configured | LLM system prompt (default: "You are a helpful AI assistant...") |
| `userId` | Optional | Guard rate limit key, log identifier (JWT userId is authoritative) |
| `metadata.sessionId` | **Always** | `ConversationManager` loads/saves conversation history |
| `responseFormat` | When not TEXT | Response format (JSON mode adds JSON instructions to system prompt) |
| `responseSchema` | Not used | Schema enforcement in JSON mode |

---

## 7. Guard Pipeline Details

Requests go through 5-stage validation before reaching the LLM:

```
Request → [1. Rate Limit] → [2. Input Validation] → [3. Injection Detection]
                           → [4. Classification] → [5. Permission] → Allow/Deny
```

| Stage | Default Implementation | Default Settings | Customizable |
|-------|----------------------|-----------------|-------------|
| Rate Limit | `DefaultRateLimitStage` | 20/min, 200/hour | application.yml |
| Input Validation | `DefaultInputValidationStage` | Max 10,000 characters | application.yml |
| Injection Detection | `DefaultInjectionDetectionStage` | Enabled (regex-based) | application.yml |
| Classification | Not implemented (interface only) | — | Add via `@Component` |
| Permission | Not implemented (interface only) | — | Add via `@Component` |

---

## 8. Hook System Details

4 extension points in the agent execution lifecycle:

```
[BeforeAgentStart] → [Agent Loop] → [BeforeToolCall → AfterToolCall]* → [AfterAgentComplete]
```

| Hook | Timing | Example Use Cases | Failure Policy |
|------|--------|------------------|----------------|
| `BeforeAgentStartHook` | Before execution starts | Authentication, budget check, metadata enrichment | Fail-open (default) |
| `BeforeToolCallHook` | Before each tool call | Parameter validation, dangerous operation approval | Fail-open (default) |
| `AfterToolCallHook` | After each tool call | Logging, metrics, notifications | Fail-open (default) |
| `AfterAgentCompleteHook` | After execution completes | Audit logging, billing, cleanup | Fail-open (default) |

Setting `failOnError: true` causes errors in that Hook to halt execution.

---

## 9. All Configurable Properties (`arc.reactor.*`)

```yaml
arc:
  reactor:
    max-tool-calls: 10              # Max tool calls in ReAct loop
    max-tools-per-request: 20       # Max tools per request

    llm:
      default-provider: gemini      # Default LLM provider
      temperature: 0.3              # Creativity (0.0~1.0)
      max-output-tokens: 4096       # Max response length
      max-conversation-turns: 10    # Max conversation history turns
      max-context-window-tokens: 128000  # Context window token limit

    retry:
      max-attempts: 3               # LLM retry count
      initial-delay-ms: 1000        # Initial delay (ms)
      multiplier: 2.0               # Exponential backoff multiplier
      max-delay-ms: 10000           # Max delay (ms)

    concurrency:
      max-concurrent-requests: 20   # Max concurrent requests
      request-timeout-ms: 30000     # Request timeout (30 seconds)

    guard:
      enabled: true                 # Enable Guard
      rate-limit-per-minute: 10     # Requests per minute limit
      rate-limit-per-hour: 100      # Requests per hour limit
      injection-detection-enabled: true  # Prompt injection detection

    boundaries:
      input-min-chars: 1            # Min input length (characters)
      input-max-chars: 5000         # Max input length (characters)

    auth:
      jwt-secret: ""                 # HMAC signing secret (required, min 32 bytes)
      jwt-expiration-ms: 86400000    # Token validity period (24 hours)
      default-tenant-id: default     # Default tenant claim for issued JWT
      public-paths:                  # Paths accessible without authentication
        - /api/auth/login
        - /api/auth/register

    cache:
      enabled: false                # Enable response caching (opt-in)
      max-size: 1000                # Max cached entries
      ttl-minutes: 60               # Cache TTL (minutes)
      cacheable-temperature: 0.0    # Cache only when temp <= this

    circuit-breaker:
      enabled: false                # Enable circuit breaker (opt-in)
      failure-threshold: 5          # Failures before OPEN
      reset-timeout-ms: 30000       # OPEN → HALF_OPEN delay (ms)
      half-open-max-calls: 1        # Trial calls in HALF_OPEN

    fallback:
      enabled: false                # Enable graceful degradation (opt-in)
      models: []                    # Fallback models in priority order

    api-version:
      enabled: true                 # Enable API version contract filter
      current: v1                   # Current API version
      supported: v1                 # Supported versions (comma-separated)

    rag:
      enabled: false                # Enable RAG pipeline
      similarity-threshold: 0.7     # Similarity threshold
      top-k: 10                     # Number of documents to retrieve
      rerank-enabled: true          # Enable Re-ranking
      max-context-tokens: 4000      # Max RAG context tokens
```

---

## 10. Current Limitations & Known Constraints

### 10.1 Session Management

| Issue | Impact | Severity |
|-------|--------|----------|
| ~~No session list/history query API~~ | **Resolved** — `GET /api/sessions`, `GET /api/sessions/{id}` | — |
| ~~No session deletion API~~ | **Resolved** — `DELETE /api/sessions/{id}` | — |
| ~~userId hardcoded (`web-user`)~~ | **Resolved** — Per-user session isolation via JWT authentication | — |
| localStorage and MemoryStore not synchronized | UI history lost when browser data is cleared (may still exist on server) | Low |

### 10.2 Token Efficiency

| Current State | Impact | Severity |
|---------------|--------|----------|
| Only uses Sliding Window (last 10 turns) | Older conversation context lost | Low |
| Summarization not implemented | Initial context lost in long conversations | Low (10 turns is sufficient for most cases) |

> Details: [memory-rag.md — Token Management Strategy](../architecture/memory-rag.md#토큰-관리-전략--매번-전체-이력을-보내면-낭비-아닌가)

### 10.3 Deployment

| Issue | Impact | Severity |
|-------|--------|----------|
| No DB in arc-reactor-web docker-compose | Conversation context lost on server restart | High |
| ~~Model list hardcoded~~ | **Resolved** — `GET /api/models` dynamic query | — |

### 10.4 Framework

| Constraint | Reason | Workaround |
|-----------|--------|-----------|
| MDC + coroutines = unreliable log correlation | MDC is ThreadLocal-based, coroutines switch threads | Add kotlinx-coroutines-slf4j |
| `runBlocking` in `ArcToolCallbackAdapter` | Spring AI interface is synchronous | Spring AI interface constraint |
| Tool success detection uses `startsWith("Error:")` | Framework-level limitation | Implement custom ToolResultParser |

---

## 11. Auto-Configured Bean List

All beans are registered with `@ConditionalOnMissingBean`, so users can override them by registering a bean of the same type.

| Bean | Default Implementation | Condition | How to Override |
|------|----------------------|-----------|----------------|
| `ToolSelector` | `AllToolSelector` | Always | Register `@Bean` |
| `MemoryStore` | `InMemoryMemoryStore` | When no DataSource | Add DataSource → auto-switches |
| `MemoryStore` | `JdbcMemoryStore` | When DataSource + JdbcTemplate present | Register `@Bean` |
| `ConversationManager` | `DefaultConversationManager` | Always | Register `@Bean` |
| `TokenEstimator` | `DefaultTokenEstimator` | Always | Register `@Bean` |
| `RequestGuard` | Guard pipeline | `guard.enabled: true` | Register `@Bean` |
| `HookExecutor` | `DefaultHookExecutor` | Always | Add `@Component` Hook |
| `McpManager` | `DefaultMcpManager` | Always | Register `@Bean` |
| `ChatModelProvider` | Auto-discovered | When ChatModel bean exists | Register `@Bean` |
| `AgentExecutor` | `SpringAiAgentExecutor` | Always | Register `@Bean` |
| `AgentMetrics` | `NoOpAgentMetrics` | Always | Register `@Bean` |
| `ResponseFilterChain` | `ResponseFilterChain` | Always | Add `@Component` ResponseFilter |
| `ResponseCache` | `CaffeineResponseCache` | cache.enabled=true | Register `@Bean` |
| `CircuitBreaker` | `DefaultCircuitBreaker` | circuit-breaker.enabled=true | Register `@Bean` |
| `FallbackStrategy` | `ModelFallbackStrategy` | fallback.enabled=true | Register `@Bean` |
| `ErrorMessageResolver` | `DefaultErrorMessageResolver` | Always | Register `@Bean` |
| `PersonaStore` | `InMemoryPersonaStore` | When no PersonaStore | Register `@Bean` |
| `PersonaStore` | `JdbcPersonaStore` | When DataSource + JdbcTemplate present | Register `@Bean` |
| `AuthProvider` | `DefaultAuthProvider` | Always | Register `@Bean` |
| `UserStore` | `InMemoryUserStore` | No DataSource | Register `@Bean` |
| `UserStore` | `JdbcUserStore` | DataSource present | Register `@Bean` |
| `JwtTokenProvider` | — | Always | — |
| `JwtAuthWebFilter` | — | Always | — |

---

## 12. Post-Fork Customization Guide

Areas that need modification when using arc-reactor after forking:

### 12.1 Required Configuration

```bash
# .env file
GEMINI_API_KEY=your-key
ARC_REACTOR_AUTH_JWT_SECRET=replace-with-32-byte-secret
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
SPRING_DATASOURCE_USERNAME=arc
SPRING_DATASOURCE_PASSWORD=arc
```

### 12.2 Adding Tools

```kotlin
@Component  // uncomment to activate
class MyCustomTool : LocalTool {
    @Tool(description = "Tool description")
    suspend fun execute(param: String): String {
        return "result"
    }
}
```

### 12.3 Connecting PostgreSQL

```yaml
# application.yml or environment variables
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/arcreactor
    username: arc
    password: arc
  flyway:
    enabled: true  # automatic migration
```

### 12.4 Customizing Guards

```kotlin
@Component
class MyPermissionStage : GuardStage {
    override val order = 500
    override suspend fun evaluate(context: GuardContext): GuardResult {
        // custom permission check
        return GuardResult.Allowed
    }
}
```

### 12.5 Adding Hooks

```kotlin
@Component
class MyAuditHook : AfterAgentCompleteHook {
    override val order = 200
    override suspend fun execute(context: HookContext, result: AgentResult) {
        // audit logging, billing, etc.
    }
}
```
