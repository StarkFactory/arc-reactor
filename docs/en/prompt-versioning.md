# Prompt Versioning

## One-Line Summary

**Version control for system prompts — deploy, rollback, and track prompt changes safely.**

---

## Why Is This Needed?

When operating an AI Agent, you constantly modify system prompts:

```
v1: "Be friendly. Refunds within 7 days only."
  → 2 weeks in production → User feedback: "Too stiff"

v2: "Be empathetic. Refunds within 7 days. Use emojis."
  → 1 week in production → Satisfaction improved

v3: "Be empathetic. Refunds extended to 14 days."
  → Deployed → Refund costs explode → Want to rollback to v2
```

**With Personas alone:**
- Editing overwrites the previous prompt — it's gone
- No record of which prompt was used when
- Rollback requires remembering or manually saving old versions
- No way to compare "Was v2 actually better than v1?"

**Prompt Versioning solves:**
- Full version history preserved (no content is ever lost)
- DRAFT → ACTIVE → ARCHIVED lifecycle for safe deployment
- Automatic metadata tracking of which version produced which response
- One API call to rollback to any previous version

---

## Who Is This For?

| Role | Usage |
|------|-------|
| **AI Agent Developer/Operator** | Primary user. Creates, deploys, and improves prompts |
| **End-user (chat user)** | Doesn't know this feature exists. Just chats normally |

---

## How Is This Different From Personas?

| | Persona | Prompt Versioning |
|---|---|---|
| **Purpose** | Role switching ("support agent" ↔ "code reviewer") | Improve/manage prompts for the same role |
| **User** | End-user selects a role | Developer manages prompts |
| **Versions** | None (edit = overwrite) | Full history (v1, v2, v3...) |
| **Status** | None | DRAFT → ACTIVE → ARCHIVED |
| **Tracking** | None | Records which version produced each response |
| **Analogy** | Folder (categorization) | Git (change history) |

They are independent. Use both together or just one.

---

## Core Concepts

### PromptTemplate

A named container that holds multiple versions.

```
PromptTemplate:
  id: "abc-123"
  name: "customer-support"      ← unique key
  description: "Customer support bot"
```

### PromptVersion

The actual prompt text. Multiple versions exist per template.

```
PromptVersion v1: "Be friendly."     → ARCHIVED
PromptVersion v2: "Be empathetic."   → ACTIVE  ← currently in use
PromptVersion v3: "Be concise."      → DRAFT   ← still testing
```

### VersionStatus (Lifecycle)

```
DRAFT  ──activate──→  ACTIVE  ──(new version activated)──→  ARCHIVED
  │                                                            ↑
  └─────────────archive────────────────────────────────────────┘
```

- **DRAFT**: Work in progress. Not yet in production
- **ACTIVE**: Currently serving in production (at most one per template)
- **ARCHIVED**: No longer in use, but preserved for history

---

## Usage

### 1. Create a Template

```bash
curl -X POST http://localhost:8080/api/prompt-templates \
  -H "Content-Type: application/json" \
  -d '{"name": "customer-support", "description": "Customer support bot"}'
```

### 2. Create a Version (DRAFT)

```bash
curl -X POST http://localhost:8080/api/prompt-templates/{id}/versions \
  -H "Content-Type: application/json" \
  -d '{
    "content": "You are a friendly customer support agent. Refunds are available within 7 days.",
    "changeLog": "Initial version"
  }'
```

### 3. Activate After Testing

```bash
curl -X PUT http://localhost:8080/api/prompt-templates/{id}/versions/{vid}/activate
```

### 4. Use in Chat

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "I want a refund", "promptTemplateId": "{id}"}'
```

The ACTIVE version's content is automatically used as the system prompt.

### 5. Improve → New Version

```bash
# Create v2
curl -X POST http://localhost:8080/api/prompt-templates/{id}/versions \
  -H "Content-Type: application/json" \
  -d '{
    "content": "You are an empathetic customer support agent. Acknowledge the customer feelings first.",
    "changeLog": "Added empathy instructions"
  }'

# Activate v2 (v1 automatically archived)
curl -X PUT http://localhost:8080/api/prompt-templates/{id}/versions/{v2id}/activate
```

### 6. Rollback on Issues

```bash
# Reactivate v1 (v2 automatically archived)
curl -X PUT http://localhost:8080/api/prompt-templates/{id}/versions/{v1id}/activate
```

---

## Version Tracking

When chatting with `promptTemplateId`, version info is automatically recorded in response metadata:

```json
{
  "promptTemplateId": "abc-123",
  "promptVersionId": "v2-uuid",
  "promptVersion": 2
}
```

This enables:
- Comparing satisfaction scores between v1 and v2
- Tracing "Which prompt version generated this strange response?"
- Measuring the actual impact of prompt changes on results

---

## System Prompt Priority

ChatController resolves the system prompt in this order:

```
1. personaId          → Persona lookup (user selects a role)
2. promptTemplateId   → Active version lookup (developer-deployed prompt)
3. systemPrompt       → Direct override (raw prompt text in request)
4. Default Persona    → Default persona from PersonaStore
5. Hardcoded fallback → "You are a helpful AI assistant."
```

---

## REST API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/prompt-templates` | List templates |
| POST | `/api/prompt-templates` | Create template |
| GET | `/api/prompt-templates/{id}` | Get template detail (with versions) |
| PUT | `/api/prompt-templates/{id}` | Update template (name, description) |
| DELETE | `/api/prompt-templates/{id}` | Delete template (cascades to versions) |
| POST | `/api/prompt-templates/{id}/versions` | Create new version (DRAFT) |
| PUT | `/api/prompt-templates/{id}/versions/{vid}/activate` | Activate version |
| PUT | `/api/prompt-templates/{id}/versions/{vid}/archive` | Archive version |

---

## Data Model

### DB Schema

```sql
-- Templates (named containers)
prompt_templates (
    id          VARCHAR(36) PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
)

-- Versions (actual prompt content)
prompt_versions (
    id          VARCHAR(36) PRIMARY KEY,
    template_id VARCHAR(36) FK → prompt_templates(id) ON DELETE CASCADE,
    version     INT,              -- 1, 2, 3... (auto-increment)
    content     TEXT,             -- system prompt text
    status      VARCHAR(20),      -- DRAFT / ACTIVE / ARCHIVED
    change_log  TEXT,
    created_at  TIMESTAMP,
    UNIQUE(template_id, version)
)
```

### Kotlin Classes

- `PromptTemplate` — Template entity (`com.arc.reactor.prompt`)
- `PromptVersion` — Version entity
- `VersionStatus` — Enum (DRAFT, ACTIVE, ARCHIVED)
- `PromptTemplateStore` — Interface (InMemory / JDBC implementations)

---

## Reference Code

- [`PromptModels.kt`](../../src/main/kotlin/com/arc/reactor/prompt/PromptModels.kt) — Data classes
- [`PromptTemplateStore.kt`](../../src/main/kotlin/com/arc/reactor/prompt/PromptTemplateStore.kt) — Interface + InMemory implementation
- [`JdbcPromptTemplateStore.kt`](../../src/main/kotlin/com/arc/reactor/prompt/JdbcPromptTemplateStore.kt) — PostgreSQL implementation
- [`PromptTemplateController.kt`](../../src/main/kotlin/com/arc/reactor/controller/PromptTemplateController.kt) — REST API
- [`V5__create_prompt_templates.sql`](../../src/main/resources/db/migration/V5__create_prompt_templates.sql) — DB migration
