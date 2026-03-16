# Personas

> This document explains Arc Reactor's Persona system -- how named system prompt templates are managed, resolved, and applied to agent execution.

## One-Line Summary

**Manage named system prompt templates (personas) that users select by ID instead of typing system prompts manually.**

---

## Why Is It Needed?

Without personas, every agent request requires the caller to provide a full system prompt:

```
POST /api/chat
{
  "systemPrompt": "You are a customer support agent. Follow our refund policy...",
  "userPrompt": "I want a refund"
}
```

Problems:
- **Duplication**: The same system prompt is copied across every client integration
- **No central control**: Updating the prompt requires changes in every caller
- **No versioning**: When a linked prompt template is updated, all callers get stale prompts
- **No metadata**: No way to attach welcome messages, icons, or guidelines to a prompt

With personas:

```
POST /api/chat
{
  "personaId": "support-agent",
  "userPrompt": "I want a refund"
}
```

The system prompt, response guidelines, welcome message, and icon are all resolved server-side.

---

## Architecture

```
Client Request (personaId)
    │
    ▼
┌─ System Prompt Resolution ─────────────────────────────────────────┐
│                                                                     │
│  1. PersonaStore.get(personaId)                                     │
│     ├─ Found? → Persona                                            │
│     └─ Not found? → PersonaStore.getDefault()                      │
│                      ├─ Found? → Default Persona                   │
│                      └─ Not found? → Built-in fallback prompt      │
│                                                                     │
│  2. resolveEffectivePrompt(promptTemplateStore)                     │
│     ├─ promptTemplateId set?                                        │
│     │   ├─ YES → promptTemplateStore.getActiveVersion()             │
│     │   │        ├─ Found? → Use template content as base           │
│     │   │        └─ Not found? → Fall back to systemPrompt          │
│     │   └─ NO  → Use systemPrompt as base                          │
│     │                                                               │
│     └─ responseGuideline set?                                       │
│         ├─ YES → base + "\n\n" + responseGuideline                 │
│         └─ NO  → base                                              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼
  Agent Executor (uses resolved system prompt)
```

**Key design principles**:
- At most one persona can be the **default** persona (`isDefault = true`). Setting a new default automatically clears the previous one.
- Prompt resolution is **fail-safe**: if a linked prompt template lookup fails, the persona's own `systemPrompt` is used as fallback.
- Personas can be **active or inactive**. The `isActive` flag controls visibility without deletion.

---

## Data Model

```kotlin
data class Persona(
    val id: String,              // UUID or "default"
    val name: String,            // Display name (e.g. "Customer Support Agent")
    val systemPrompt: String,    // The actual system prompt text
    val isDefault: Boolean,      // At most one persona is default
    val description: String?,    // Short description for admin UI
    val responseGuideline: String?, // Appended to systemPrompt
    val welcomeMessage: String?, // Initial greeting shown on persona selection
    val icon: String?,           // Emoji or short icon identifier for UI
    val isActive: Boolean,       // Whether available for selection
    val promptTemplateId: String?, // Optional linked versioned prompt template
    val createdAt: Instant,
    val updatedAt: Instant
)
```

### System Prompt Composition

The effective system prompt is built by `resolveEffectivePrompt()`:

1. **Base prompt**: If `promptTemplateId` is set and a `PromptTemplateStore` is available, the active version of the linked template is used. Otherwise, `systemPrompt` is the base.
2. **Response guideline**: If `responseGuideline` is non-blank, it is appended after a double newline.

```
[base prompt from template or systemPrompt]

[responseGuideline]
```

---

## Store Implementations

### InMemoryPersonaStore (Default)

- Thread-safe via `ConcurrentHashMap` + `synchronized` blocks for default enforcement
- Pre-loads a "Default Assistant" persona on construction
- Not persistent -- data lost on server restart

### JdbcPersonaStore (PostgreSQL)

- Persistent across server restarts
- Single default persona enforced via database transactions
- Partial unique index ensures at most one default at the database level
- Uses `TransactionTemplate` for atomic default-swap operations

---

## Default Persona

At most one persona can have `isDefault = true`. The invariant is enforced at two levels:

1. **Application level**: Both `InMemoryPersonaStore` and `JdbcPersonaStore` clear the existing default before setting a new one.
2. **Database level**: A partial unique index on `is_default WHERE is_default = TRUE` prevents concurrent violations.

When no `personaId` is specified in a request, the scheduler and agent executor fall back to the default persona's resolved prompt.

---

## Integration with Agent Execution

Personas are used in two contexts:

### 1. Direct Chat (ChatController)

The caller provides a `personaId`. The controller resolves the effective system prompt and passes it to `AgentCommand.systemPrompt`.

### 2. Scheduler (DynamicSchedulerService)

For AGENT-mode scheduled jobs, system prompt resolution follows this priority:

1. `agentSystemPrompt` (explicit override on the job)
2. `personaId` on the job --> `PersonaStore.get(personaId).resolveEffectivePrompt()`
3. Default persona --> `PersonaStore.getDefault().resolveEffectivePrompt()`
4. Built-in fallback prompt

---

## REST API

All write endpoints require ADMIN role. Base path: `/api/personas`

### List Personas
```
GET /api/personas?activeOnly=false
```

### Get Persona
```
GET /api/personas/{personaId}
```

### Create Persona
```
POST /api/personas
Content-Type: application/json

{
  "name": "Customer Support Agent",
  "systemPrompt": "You are a customer support agent. Follow our refund policy strictly...",
  "isDefault": false,
  "description": "Handles customer inquiries and refund requests",
  "responseGuideline": "Always respond in a friendly tone. Include order numbers when available.",
  "welcomeMessage": "Hello! How can I help you today?",
  "icon": "headset",
  "promptTemplateId": "support-prompt-v2",
  "isActive": true
}
```

Response: `201 Created`

### Update Persona (Partial)
```
PUT /api/personas/{personaId}
Content-Type: application/json

{
  "responseGuideline": "Updated: Be formal and concise.",
  "isDefault": true
}
```

Only provided fields are changed. Setting `isDefault: true` clears the previous default.

To clear a nullable field, send an empty string: `"description": ""`.

### Delete Persona
```
DELETE /api/personas/{personaId}
```

Response: `204 No Content` (idempotent)

---

## Configuration

Personas do not have a dedicated configuration block. They are always available when a `PersonaStore` bean is registered.

The `InMemoryPersonaStore` is auto-configured by default. When PostgreSQL and `JdbcTemplate` are available, `JdbcPersonaStore` is registered with `@Primary`.

---

## Database Schema

### `personas` (V2 + V8 + V29 + V33)

```sql
CREATE TABLE personas (
    id                 VARCHAR(36)   PRIMARY KEY,
    name               VARCHAR(200)  NOT NULL,
    system_prompt      TEXT          NOT NULL,
    is_default         BOOLEAN       NOT NULL DEFAULT FALSE,
    description        TEXT,
    response_guideline TEXT,
    welcome_message    TEXT,
    icon               VARCHAR(20),
    is_active          BOOLEAN       NOT NULL DEFAULT TRUE,
    prompt_template_id VARCHAR(36),
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- At most one default persona
CREATE UNIQUE INDEX idx_personas_single_default
    ON personas (is_default)
    WHERE is_default = TRUE;
```

Seed data:
```sql
INSERT INTO personas (id, name, system_prompt, is_default)
VALUES ('default', 'Default Assistant', '...', TRUE);
```

---

## Common Pitfalls

1. **Default persona swap is transactional**: Setting `isDefault: true` on persona A clears `isDefault` on the previous default. If the transaction fails mid-way, the old default is preserved.
2. **Prompt template fallback**: If `promptTemplateId` points to a deleted or inactive template, the persona falls back to its own `systemPrompt` silently (logged as warning).
3. **Nullable field clearing**: To clear `description`, `responseGuideline`, `welcomeMessage`, `icon`, or `promptTemplateId`, send an empty string `""`. Sending `null` (or omitting the field) means "no change".
4. **Active vs. deleted**: Use `isActive = false` to hide a persona from selection without losing its data. Use DELETE only for permanent removal.

---

## Key Files

| File | Role |
|------|------|
| `persona/PersonaStore.kt` | Persona data class, PersonaStore interface, InMemoryPersonaStore, resolveEffectivePrompt() |
| `persona/JdbcPersonaStore.kt` | JDBC implementation with transactional default enforcement |
| `controller/PersonaController.kt` | REST API for persona CRUD |
| `scheduler/DynamicSchedulerService.kt` | Uses PersonaStore for AGENT-mode system prompt resolution |
