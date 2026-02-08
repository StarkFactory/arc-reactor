# Session & Persona Management

## One-Line Summary

**Manage conversation history via session REST APIs and centrally manage system prompts with personas.** When authentication is enabled, per-user session isolation is automatically applied.

---

## Session Management

### API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/sessions` | List sessions |
| `GET` | `/api/sessions/{id}` | Session details (all messages) |
| `DELETE` | `/api/sessions/{id}` | Delete session |
| `GET` | `/api/models` | List available LLM models |

### GET /api/sessions — Session List

```bash
curl http://localhost:8080/api/sessions
```

```json
[
  {
    "sessionId": "a1b2c3d4-...",
    "messageCount": 12,
    "lastActivity": 1707350413500,
    "preview": "Python이란 뭐야?"
  },
  {
    "sessionId": "e5f6g7h8-...",
    "messageCount": 4,
    "lastActivity": 1707349800000,
    "preview": "3 + 5는 얼마야?"
  }
]
```

- `preview`: First 50 characters of the first user message
- `lastActivity`: Epoch milliseconds of the last message
- Sorted by most recent activity
- **When authentication is enabled**: Only returns sessions belonging to the current user

### GET /api/sessions/{id} — Session Details

```bash
curl http://localhost:8080/api/sessions/a1b2c3d4-...
```

```json
{
  "sessionId": "a1b2c3d4-...",
  "messages": [
    { "role": "user", "content": "안녕하세요", "timestamp": 1707350400000 },
    { "role": "assistant", "content": "안녕하세요! 무엇을 도와드릴까요?", "timestamp": 1707350402300 }
  ]
}
```

- **When authentication is enabled**: Accessing another user's session returns 403 Forbidden

### DELETE /api/sessions/{id} — Delete Session

```bash
curl -X DELETE http://localhost:8080/api/sessions/a1b2c3d4-...
# → 204 No Content
```

- Completely deletes session data from the MemoryStore
- **When authentication is enabled**: Deleting another user's session returns 403 Forbidden

### GET /api/models — Model List

```bash
curl http://localhost:8080/api/models
```

```json
{
  "models": [
    { "name": "gemini", "isDefault": true },
    { "name": "openai", "isDefault": false },
    { "name": "anthropic", "isDefault": false }
  ],
  "defaultModel": "gemini"
}
```

- Only auto-detects providers registered as `implementation` in `build.gradle.kts`
- The `arc.reactor.llm.default-provider` configuration value is marked as `isDefault: true`

---

## Session Architecture

### Data Flow

```
[User enters message]
     │
     ├─→ Frontend: Append message to session.messages[] → Save to localStorage
     │
     └─→ POST /api/chat/stream { metadata: { sessionId: UUID } }
              │
              ├─→ ConversationManager.loadHistory(sessionId)
              │     → Load previous conversation from MemoryStore
              │     → Pass as context to LLM
              │
              ├─→ LLM execution (context + current message)
              │
              └─→ ConversationManager.saveHistory(sessionId, content)
                    → Save user + assistant messages to MemoryStore
```

### Dual Storage Architecture

Conversation data is stored **independently in two locations**:

| Storage | Location | Contents | Purpose |
|---------|----------|----------|---------|
| **localStorage** | Browser | Session list + all messages + metadata | UI display, session switching, offline cache |
| **MemoryStore** | Server | role + content + timestamp | LLM conversation context |

- localStorage: `id`, `role`, `content`, `toolsUsed`, `error`, `timestamp`, `durationMs` (rich metadata)
- MemoryStore: `role`, `content`, `timestamp` (only the minimum information needed for LLM context)
- The two stores are **not synchronized** -- each stores and reads independently

### MemoryStore Implementations

| | InMemoryMemoryStore | JdbcMemoryStore |
|---|---|---|
| **Storage** | Caffeine cache (in-memory) | PostgreSQL (on-disk) |
| **Max sessions** | 1,000 (auto-evicted via LRU) | Unlimited |
| **Max messages per session** | 50 | 100 |
| **On overflow** | FIFO (oldest deleted first) | FIFO (DELETE + LIMIT) |
| **Server restart** | Lost | Persisted |
| **Auto-switching** | Default | Automatically activated when a DataSource bean is detected |

### Session ID Propagation Path

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

---

## Per-User Session Isolation

Automatically activated when authentication is enabled.

### Backend Isolation

```
[JwtAuthWebFilter]
  │ JWT → Extract userId → exchange.attributes["userId"]
  ▼
[ChatController]
  │ resolveUserId(): exchange > request.userId > "anonymous"
  ▼
[ConversationManager]
  │ addMessage(sessionId, role, content, userId)
  ▼
[MemoryStore]
  │ sessionOwners[sessionId] = userId (recorded on first message)
  ▼
[SessionController]
  │ listSessions: listSessionsByUserId(userId) → only that user's sessions
  │ getSession:   isSessionOwner() check → 403 on mismatch
  │ deleteSession: isSessionOwner() check → 403 on mismatch
```

Ownership determination logic (`isSessionOwner`):
- `owner == null` (legacy data, userId not recorded) → access allowed
- `owner == userId` → access allowed
- `owner == "anonymous"` → access allowed
- Otherwise → 403 Forbidden

### Frontend Isolation

- localStorage keys are separated per userId: `arc-reactor-sessions:{userId}`, `arc-reactor-settings:{userId}`
- `ChatProvider key={user?.id}` — When the user changes, the entire React context remounts
- No session data interference on login/logout

---

## Persona Management

A persona is a **named system prompt template**. Administrators manage prompts centrally, and users select them by name.

### API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/personas` | List all |
| `GET` | `/api/personas/{id}` | Get by ID |
| `POST` | `/api/personas` | Create |
| `PUT` | `/api/personas/{id}` | Update (partial update) |
| `DELETE` | `/api/personas/{id}` | Delete |

### GET /api/personas

```json
[
  {
    "id": "default",
    "name": "Default Assistant",
    "systemPrompt": "You are a helpful AI assistant...",
    "isDefault": true,
    "createdAt": 1707350400000,
    "updatedAt": 1707350400000
  },
  {
    "id": "550e8400-...",
    "name": "Python Expert",
    "systemPrompt": "You are an expert Python developer...",
    "isDefault": false,
    "createdAt": 1707350500000,
    "updatedAt": 1707350500000
  }
]
```

### POST /api/personas — Create

```bash
curl -X POST http://localhost:8080/api/personas \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Customer Support",
    "systemPrompt": "You are a customer support agent. Be polite and helpful.",
    "isDefault": false
  }'
```

### PUT /api/personas/{id} — Partial Update

```bash
# Change only the name (other fields remain unchanged)
curl -X PUT http://localhost:8080/api/personas/550e8400-... \
  -H "Content-Type: application/json" \
  -d '{ "name": "Python & JS Expert" }'
```

- Fields set to `null` retain their existing values (partial update)
- Setting `isDefault: true` automatically unsets the previous default

### DELETE /api/personas/{id}

```bash
curl -X DELETE http://localhost:8080/api/personas/550e8400-...
# → 204 No Content (returns 204 even for non-existent IDs)
```

### System Prompt Resolution Priority

The order in which the actual system prompt is determined in ChatController:

```
1. If request.personaId exists → Look up in PersonaStore → Use that prompt
2. If request.systemPrompt exists → Use directly (when entered manually from the frontend)
3. Look up the default persona in PersonaStore → Use if found
4. Hardcoded fallback: "You are a helpful AI assistant..."
```

### PersonaStore Implementations

| | InMemoryPersonaStore | JdbcPersonaStore |
|---|---|---|
| **Storage** | ConcurrentHashMap | PostgreSQL `personas` table |
| **Initial data** | "Default Assistant" created in code | Seeded via Flyway V2 |
| **Server restart** | Lost (only the default persona is recreated) | Persisted |
| **Auto-switching** | Default | Automatically activated when a DataSource bean is detected |

### isDefault Uniqueness Guarantee

There is **always at most one** persona with `isDefault = true`:
- On `save(persona)`: If `isDefault = true`, the existing default is unset first
- On `update()`: If `isDefault = true`, the existing default is unset first

---

## DB Schema

### personas Table (Flyway V2)

```sql
CREATE TABLE IF NOT EXISTS personas (
    id            VARCHAR(36)   PRIMARY KEY,
    name          VARCHAR(200)  NOT NULL,
    system_prompt TEXT          NOT NULL,
    is_default    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Default persona seed
INSERT INTO personas (id, name, system_prompt, is_default)
VALUES ('default', 'Default Assistant',
        'You are a helpful AI assistant. ...', TRUE);
```

### conversation_messages Table (Flyway V1 + V4)

```sql
-- V1: Base table
CREATE TABLE conversation_messages (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(255) NOT NULL,
    role        VARCHAR(20)  NOT NULL,    -- user, assistant, system, tool
    content     TEXT         NOT NULL,
    timestamp   BIGINT       NOT NULL,    -- epoch millis
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- V4: Per-user isolation (when authentication is enabled)
ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(36) NOT NULL DEFAULT 'anonymous';
```

---

## Frontend Integration

### Session Management Flow

```
[App Start]
  │
  ├─→ Load session list from localStorage
  ├─→ Verify server-side sessions via GET /api/sessions
  │
  ▼
[Session Selection]
  │
  ├─→ Set activeSessionId
  ├─→ Load messages from localStorage (display immediately)
  │
  ▼
[Send Message]
  │
  ├─→ POST /api/chat/stream { metadata: { sessionId } }
  ├─→ After receiving response, update localStorage + UI
  │
  ▼
[Delete Session]
  │
  ├─→ DELETE /api/sessions/{id} (server-side cleanup)
  └─→ Also remove from localStorage
```

### Persona Selection (Frontend)

`PersonaSelector` component in `SettingsPanel`:

1. Display persona list in a dropdown (`GET /api/personas`)
2. Select a persona → Include `personaId` in the ChatRequest
3. Select "Custom" mode → Enter and send `systemPrompt` directly
4. Inline CRUD -- Add/edit/delete personas within the dropdown

---

## Reference Code

- [`controller/SessionController.kt`](../../src/main/kotlin/com/arc/reactor/controller/SessionController.kt) -- Session/model API
- [`controller/PersonaController.kt`](../../src/main/kotlin/com/arc/reactor/controller/PersonaController.kt) -- Persona CRUD API
- [`controller/ChatController.kt`](../../src/main/kotlin/com/arc/reactor/controller/ChatController.kt) -- System prompt resolution logic
- [`memory/ConversationMemory.kt`](../../src/main/kotlin/com/arc/reactor/memory/ConversationMemory.kt) -- MemoryStore interface
- [`memory/ConversationManager.kt`](../../src/main/kotlin/com/arc/reactor/memory/ConversationManager.kt) -- Conversation history lifecycle
- [`persona/PersonaStore.kt`](../../src/main/kotlin/com/arc/reactor/persona/PersonaStore.kt) -- PersonaStore interface + InMemory implementation
- [`persona/JdbcPersonaStore.kt`](../../src/main/kotlin/com/arc/reactor/persona/JdbcPersonaStore.kt) -- PostgreSQL implementation
- [`V1__create_conversation_messages.sql`](../../src/main/resources/db/migration/V1__create_conversation_messages.sql) -- Conversation table
- [`V2__create_personas.sql`](../../src/main/resources/db/migration/V2__create_personas.sql) -- Persona table
