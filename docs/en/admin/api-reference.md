# Admin API Reference

This document covers every admin-accessible endpoint in Arc Reactor. It is sourced directly from the controller source code. All paths, request fields, response fields, and status codes reflect the actual implementation.

---

## Authentication

### Runtime Requirement

Arc Reactor runtime requires `arc.reactor.auth.enabled=true`. Startup fails fast when authentication is disabled.

Set `arc.reactor.auth.enabled=true` and `arc.reactor.auth.jwt-secret=<secret>` (minimum 32 bytes, generate with `openssl rand -base64 32`).

**Step 1: Register or login**

```
POST /api/auth/register
POST /api/auth/login
```

Both return a `token` field in the response body.

**Step 2: Send the token**

Include the token on every subsequent request:

```
Authorization: Bearer <token>
```

Requests without a valid token to protected endpoints return `401 Unauthorized`. Requests with a valid token but insufficient role (USER trying admin endpoints) return `403 Forbidden`.

### Required Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes (write operations) | Must be `application/json` |
| `Authorization` | When auth enabled | `Bearer <JWT>` |
| `X-Tenant-Id` | Optional (arc-admin module) | Tenant isolation identifier. Alphanumeric, hyphens, underscores. Max 64 chars. |

### Admin vs User Access

- **Admin required**: Endpoints that create, modify, or delete configuration.
- **Authenticated**: Endpoints that require a valid JWT but not necessarily the ADMIN role.
- **Public**: No authentication required.

### Error Response Format

All error responses use this standard DTO:

```json
{
  "error": "Description of the error",
  "details": "Optional additional details",
  "timestamp": "2026-02-28T12:00:00Z"
}
```

`403 Forbidden` responses always include a body — they are never empty.

---

## Authentication Endpoints

> **Condition**: Only registered when `arc.reactor.auth.enabled=true`.

### POST /api/auth/register

Register a new user account and receive a JWT.

**Auth**: Public

**Request body**:
```json
{
  "email": "user@example.com",
  "password": "mypassword123",
  "name": "Jane Smith"
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `email` | string | Yes | Valid email format |
| `password` | string | Yes | Min 8 characters |
| `name` | string | Yes | Non-blank |

**Response `201 Created`**:
```json
{
  "token": "<JWT>",
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "name": "Jane Smith",
    "role": "USER"
  }
}
```

**Response codes**: `201` success | `400` validation error | `409` email already registered

---

### POST /api/auth/login

Authenticate with email and password, receive a JWT.

**Auth**: Public

**Request body**:
```json
{
  "email": "user@example.com",
  "password": "mypassword123"
}
```

**Response `200 OK`**:
```json
{
  "token": "<JWT>",
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "name": "Jane Smith",
    "role": "USER"
  }
}
```

**Response codes**: `200` success | `400` validation error | `401` invalid credentials

---

### GET /api/auth/me

Get the current authenticated user's profile.

**Auth**: Authenticated (requires valid JWT)

**Response `200 OK`**:
```json
{
  "id": "uuid",
  "email": "user@example.com",
  "name": "Jane Smith",
  "role": "USER"
}
```

**Response codes**: `200` success | `401` missing or invalid JWT | `404` user not found

---

### POST /api/auth/change-password

Change the current user's password.

**Auth**: Authenticated (requires valid JWT)

**Request body**:
```json
{
  "currentPassword": "oldpassword",
  "newPassword": "newpassword123"
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `currentPassword` | string | Yes | Non-blank |
| `newPassword` | string | Yes | Min 8 characters |

**Response `200 OK`**:
```json
{
  "message": "Password changed successfully"
}
```

**Response codes**: `200` success | `400` wrong current password or unsupported auth provider | `401` unauthenticated | `404` user not found

---

## Persona Management

Base path: `/api/personas`

Personas are named system prompt configurations. The default persona is applied when no persona or prompt template is specified in a chat request.

### GET /api/personas

List all personas.

**Auth**: Public (no auth required even when auth is enabled)

**Response `200 OK`**:
```json
[
  {
    "id": "uuid",
    "name": "Helpful Assistant",
    "systemPrompt": "You are a helpful AI assistant...",
    "isDefault": true,
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
]
```

---

### GET /api/personas/{personaId}

Get a persona by ID.

**Auth**: Public

**Response `200 OK`**: Single `PersonaResponse` object (same shape as list item).

**Response codes**: `200` success | `404` not found

---

### POST /api/personas

Create a new persona.

**Auth**: Admin required

**Request body**:
```json
{
  "name": "Customer Support",
  "systemPrompt": "You are a customer support agent...",
  "isDefault": false
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `name` | string | Yes | — | Non-blank |
| `systemPrompt` | string | Yes | — | Non-blank |
| `isDefault` | boolean | No | `false` | — |

**Response `201 Created`**: `PersonaResponse` object

**Response codes**: `201` created | `400` validation error | `403` admin required

---

### PUT /api/personas/{personaId}

Update an existing persona. Only provided fields are changed (partial update).

**Auth**: Admin required

**Request body** (all fields optional):
```json
{
  "name": "Updated Name",
  "systemPrompt": "Updated prompt...",
  "isDefault": true
}
```

**Response `200 OK`**: Updated `PersonaResponse` object

**Response codes**: `200` success | `400` validation error | `403` admin required | `404` not found

---

### DELETE /api/personas/{personaId}

Delete a persona. Idempotent — returns `204` even if the persona does not exist.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required

---

## Prompt Templates

Base path: `/api/prompt-templates`

Versioned system prompt templates. Each template holds multiple versions with lifecycle status: `DRAFT` → `ACTIVE` → `ARCHIVED`. Only one version may be `ACTIVE` at a time.

### GET /api/prompt-templates

List all prompt templates.

**Auth**: Public

**Response `200 OK`**:
```json
[
  {
    "id": "uuid",
    "name": "Support Bot v2",
    "description": "Customer support template",
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
]
```

---

### GET /api/prompt-templates/{templateId}

Get a template with all its versions.

**Auth**: Public

**Response `200 OK`**:
```json
{
  "id": "uuid",
  "name": "Support Bot v2",
  "description": "Customer support template",
  "activeVersion": {
    "id": "version-uuid",
    "templateId": "uuid",
    "version": 3,
    "content": "You are a support agent...",
    "status": "ACTIVE",
    "changeLog": "Improved tone",
    "createdAt": 1700000000000
  },
  "versions": [...],
  "createdAt": 1700000000000,
  "updatedAt": 1700000000000
}
```

**Response codes**: `200` success | `404` not found

---

### POST /api/prompt-templates

Create a new prompt template.

**Auth**: Admin required

**Request body**:
```json
{
  "name": "Support Bot v2",
  "description": "Customer support template"
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `name` | string | Yes | — | Non-blank |
| `description` | string | No | `""` | — |

**Response `201 Created`**: `TemplateResponse` object

**Response codes**: `201` created | `400` validation error | `403` admin required

---

### PUT /api/prompt-templates/{templateId}

Update template metadata (name and description). Partial update — only provided fields are changed.

**Auth**: Admin required

**Request body** (all optional):
```json
{
  "name": "New Name",
  "description": "New description"
}
```

**Response `200 OK`**: Updated `TemplateResponse` object

**Response codes**: `200` success | `400` validation error | `403` admin required | `404` not found

---

### DELETE /api/prompt-templates/{templateId}

Delete a template and all its versions. Idempotent.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required

---

### POST /api/prompt-templates/{templateId}/versions

Create a new version for a template. New versions start in `DRAFT` status.

**Auth**: Admin required

**Request body**:
```json
{
  "content": "You are a support agent specialized in...",
  "changeLog": "Added empathy instructions"
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `content` | string | Yes | — | Non-blank |
| `changeLog` | string | No | `""` | — |

**Response `201 Created`**: `VersionResponse` object

**Response codes**: `201` created | `400` validation error | `403` admin required | `404` template not found

---

### PUT /api/prompt-templates/{templateId}/versions/{versionId}/activate

Activate a version. The previously active version is automatically archived.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**: Updated `VersionResponse` object with `status: "ACTIVE"`

**Response codes**: `200` success | `403` admin required | `404` template or version not found

---

### PUT /api/prompt-templates/{templateId}/versions/{versionId}/archive

Archive a version.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**: Updated `VersionResponse` object with `status: "ARCHIVED"`

**Response codes**: `200` success | `403` admin required | `404` template or version not found

---

## MCP Server Management

Base path: `/api/mcp/servers`

Dynamic MCP (Model Context Protocol) server registration and lifecycle management. MCP servers provide tools to the agent. Supported transport types: `SSE`, `STDIO`. HTTP transport is not supported.

### GET /api/mcp/servers

List all registered MCP servers with connection status.

**Auth**: Public

**Response `200 OK`**:
```json
[
  {
    "id": "uuid",
    "name": "my-mcp-server",
    "description": "Jira and Confluence tools",
    "transportType": "SSE",
    "autoConnect": true,
    "status": "CONNECTED",
    "toolCount": 12,
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
]
```

Status values: `PENDING`, `CONNECTED`, `DISCONNECTED`, `FAILED`

---

### POST /api/mcp/servers

Register a new MCP server and optionally connect to it.

**Auth**: Admin required

**Request body (SSE transport)**:
```json
{
  "name": "my-mcp-server",
  "description": "Provides Jira tools",
  "transportType": "SSE",
  "config": {
    "url": "http://localhost:8081/sse",
    "adminUrl": "http://localhost:8081",
    "adminToken": "secret-admin-token"
  },
  "autoConnect": true
}
```

**Request body (STDIO transport)**:
```json
{
  "name": "fs-server",
  "transportType": "STDIO",
  "config": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
  },
  "autoConnect": true
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `name` | string | Yes | — | Non-blank, max 100 chars |
| `description` | string | No | `null` | Max 500 chars |
| `transportType` | string | No | `"SSE"` | `SSE` or `STDIO` |
| `config` | object | No | `{}` | Max 20 entries |
| `version` | string | No | `null` | — |
| `autoConnect` | boolean | No | `true` | — |

**Response `201 Created`**: `McpServerResponse` object

**Response codes**: `201` registered | `400` invalid transport or blocked by security allowlist | `403` admin required | `409` server name already exists

---

### GET /api/mcp/servers/{name}

Get server details including connection status and available tools.

**Auth**: Public

**Response `200 OK`**:
```json
{
  "id": "uuid",
  "name": "my-mcp-server",
  "description": "Provides Jira tools",
  "transportType": "SSE",
  "config": {
    "url": "http://localhost:8081/sse"
  },
  "version": null,
  "autoConnect": true,
  "status": "CONNECTED",
  "tools": ["jira_create_issue", "jira_search_issues", "confluence_get_page"],
  "createdAt": 1700000000000,
  "updatedAt": 1700000000000
}
```

**Response codes**: `200` success | `404` not found

---

### PUT /api/mcp/servers/{name}

Update MCP server configuration. Reconnection is required to apply transport changes.

**Auth**: Admin required

**Request body** (all fields optional):
```json
{
  "description": "Updated description",
  "transportType": "SSE",
  "config": {
    "url": "http://new-host:8081/sse"
  },
  "version": "1.2",
  "autoConnect": false
}
```

**Response `200 OK`**: Updated `McpServerResponse` object

**Response codes**: `200` success | `400` invalid transport type | `403` admin required | `404` not found

---

### DELETE /api/mcp/servers/{name}

Disconnect and remove an MCP server.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` removed | `403` admin required | `404` not found

---

### POST /api/mcp/servers/{name}/connect

Connect to a registered MCP server.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**:
```json
{
  "status": "CONNECTED",
  "tools": ["tool_one", "tool_two"]
}
```

**Response codes**: `200` connected | `403` admin required | `404` not found | `503` connection failed

---

### POST /api/mcp/servers/{name}/disconnect

Disconnect from an MCP server without removing it.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**:
```json
{
  "status": "DISCONNECTED"
}
```

**Response codes**: `200` disconnected | `403` admin required | `404` not found

---

## MCP Access Policy

Base path: `/api/mcp/servers/{name}/access-policy`

Proxy controller for managing access policies on MCP servers that expose an `/admin/access-policy` endpoint (e.g., Atlassian MCP servers). Requires the MCP server's `config` to contain `adminToken` and either `adminUrl` or `url`.

### GET /api/mcp/servers/{name}/access-policy

Retrieve the current access policy from the MCP server's admin API.

**Auth**: Admin required

**Response `200 OK`**: The policy object returned by the upstream MCP server admin API (forwarded as-is).

**Response codes**: `200` success | `400` invalid server config (missing `adminUrl` or `adminToken`) | `403` admin required | `404` server not found | `504` upstream timeout

---

### PUT /api/mcp/servers/{name}/access-policy

Update the access policy on the MCP server's admin API.

**Auth**: Admin required

**Request body**:
```json
{
  "allowedJiraProjectKeys": ["PROJ", "MYTEAM"],
  "allowedConfluenceSpaceKeys": ["ENG", "DOCS"]
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `allowedJiraProjectKeys` | array of string | No | Max 200 items. Each key: `^[A-Z][A-Z0-9_]*$`, max 50 chars, no leading/trailing whitespace |
| `allowedConfluenceSpaceKeys` | array of string | No | Max 200 items. Each key: `^[A-Za-z0-9][A-Za-z0-9_-]*$`, max 64 chars, no leading/trailing whitespace |

**Response `200 OK`**: The updated policy from the upstream MCP server.

**Response codes**: `200` updated | `400` validation error or invalid server config | `403` admin required | `404` server not found | `504` upstream timeout

---

### DELETE /api/mcp/servers/{name}/access-policy

Clear the dynamic access policy on the MCP server, resetting it to environment defaults.

**Auth**: Admin required

**Response `200 OK`**: Confirmation from the upstream MCP server.

**Response codes**: `200` cleared | `400` invalid server config | `403` admin required | `404` server not found

---

## Tool Policy

Base path: `/api/tool-policy`

> **Condition**: Only registered when `arc.reactor.tool-policy.dynamic.enabled=true`.

Controls which tools are classified as "write" tools and which channels are denied write access.

### GET /api/tool-policy

Get the current tool policy state including both the effective (in-use) policy and the stored (database) policy.

**Auth**: Admin required

**Response `200 OK`**:
```json
{
  "configEnabled": true,
  "dynamicEnabled": true,
  "effective": {
    "enabled": true,
    "writeToolNames": ["jira_create_issue", "jira_update_issue"],
    "denyWriteChannels": ["readonly-channel"],
    "allowWriteToolNamesInDenyChannels": [],
    "allowWriteToolNamesByChannel": {},
    "denyWriteMessage": "Error: This tool is not allowed in this channel",
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  },
  "stored": null
}
```

**Response codes**: `200` success | `403` admin required

---

### PUT /api/tool-policy

Update the stored tool policy. Takes effect immediately.

**Auth**: Admin required

**Request body**:
```json
{
  "enabled": true,
  "writeToolNames": ["jira_create_issue", "jira_update_issue"],
  "denyWriteChannels": ["readonly-channel"],
  "allowWriteToolNamesInDenyChannels": [],
  "allowWriteToolNamesByChannel": {
    "special-channel": ["jira_create_issue"]
  },
  "denyWriteMessage": "Write tools are not allowed here"
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `enabled` | boolean | No | `false` | — |
| `writeToolNames` | array of string | No | `[]` | Max 500 entries |
| `denyWriteChannels` | array of string | No | `[]` | Max 50 entries. Stored lowercase |
| `allowWriteToolNamesInDenyChannels` | array of string | No | `[]` | Max 500 entries |
| `allowWriteToolNamesByChannel` | object (channel → set of tool names) | No | `{}` | Max 200 channels |
| `denyWriteMessage` | string | No | `"Error: This tool is not allowed in this channel"` | Max 500 chars |

**Response `200 OK`**: `ToolPolicyResponse` object (the saved policy)

**Response codes**: `200` success | `400` validation error | `403` admin required

---

### DELETE /api/tool-policy

Delete the stored tool policy, reverting to config-file defaults.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required

---

## Output Guard Rules

Base path: `/api/output-guard/rules`

Dynamic regex-based rules that inspect and optionally block or mask LLM output before it is returned to the user. Rules are applied in ascending priority order.

Actions: `BLOCK` (reject the response), `MASK` (replace matched text), `LOG` (log without modifying).

### GET /api/output-guard/rules

List all output guard rules.

**Auth**: Admin required

**Response `200 OK`**:
```json
[
  {
    "id": "uuid",
    "name": "PII Credit Card",
    "pattern": "\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b",
    "action": "MASK",
    "priority": 10,
    "enabled": true,
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
]
```

**Response codes**: `200` success | `403` admin required

---

### GET /api/output-guard/rules/audits

List output guard rule audit logs.

**Auth**: Admin required

**Query parameters**:

| Parameter | Type | Required | Default | Constraints |
|-----------|------|----------|---------|-------------|
| `limit` | integer | No | `100` | Min 1, max 1000 |

**Response `200 OK`**:
```json
[
  {
    "id": "uuid",
    "ruleId": "rule-uuid",
    "action": "CREATE",
    "actor": "user-id",
    "detail": "name=PII Credit Card, action=MASK, priority=10, enabled=true",
    "createdAt": 1700000000000
  }
]
```

**Response codes**: `200` success | `403` admin required

---

### POST /api/output-guard/rules

Create a new output guard rule.

**Auth**: Admin required

**Request body**:
```json
{
  "name": "PII Credit Card",
  "pattern": "\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b",
  "action": "MASK",
  "priority": 10,
  "enabled": true
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `name` | string | Yes | — | Non-blank, max 120 chars |
| `pattern` | string | Yes | — | Non-blank, valid Java regex, max 5000 chars |
| `action` | string | No | `"MASK"` | `BLOCK`, `MASK`, or `LOG` (case-insensitive) |
| `priority` | integer | No | `100` | Min 1, max 10000 |
| `enabled` | boolean | No | `true` | — |

**Response `201 Created`**: `OutputGuardRuleResponse` object

**Response codes**: `201` created | `400` invalid action or invalid regex | `403` admin required

---

### PUT /api/output-guard/rules/{id}

Update an output guard rule. Partial update — only provided fields are changed.

**Auth**: Admin required

**Request body** (all optional):
```json
{
  "name": "Updated Name",
  "pattern": "new-regex",
  "action": "BLOCK",
  "priority": 5,
  "enabled": false
}
```

**Response `200 OK`**: Updated `OutputGuardRuleResponse` object

**Response codes**: `200` success | `400` invalid action or regex | `403` admin required | `404` rule not found

---

### DELETE /api/output-guard/rules/{id}

Delete an output guard rule.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required | `404` rule not found

---

### POST /api/output-guard/rules/simulate

Dry-run the output guard policy against a given content string without modifying any rule state.

**Auth**: Admin required

**Request body**:
```json
{
  "content": "My credit card is 4111 1111 1111 1111",
  "includeDisabled": false
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `content` | string | Yes | — | Non-blank, max 50000 chars |
| `includeDisabled` | boolean | No | `false` | When `true`, disabled rules are also evaluated |

**Response `200 OK`**:
```json
{
  "originalContent": "My credit card is 4111 1111 1111 1111",
  "resultContent": "My credit card is [MASKED]",
  "blocked": false,
  "modified": true,
  "blockedByRuleId": null,
  "blockedByRuleName": null,
  "matchedRules": [
    {
      "ruleId": "uuid",
      "ruleName": "PII Credit Card",
      "action": "MASK",
      "priority": 10
    }
  ],
  "invalidRules": []
}
```

**Response codes**: `200` success | `400` validation error | `403` admin required

---

## Intent Classification

Base path: `/api/intents`

> **Condition**: Only registered when `arc.reactor.intent.enabled=true`.

Manage intent definitions used for classifying incoming user requests and applying intent-specific agent profiles.

### GET /api/intents

List all intent definitions.

**Auth**: Admin required

**Response `200 OK`**:
```json
[
  {
    "name": "order_inquiry",
    "description": "User is asking about an existing order",
    "examples": ["Where is my order?", "Track my package"],
    "keywords": ["order", "track", "package"],
    "profile": {
      "model": null,
      "temperature": null,
      "maxToolCalls": null,
      "allowedTools": null,
      "systemPrompt": null,
      "responseFormat": null
    },
    "enabled": true,
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
]
```

**Response codes**: `200` success | `403` admin required

---

### GET /api/intents/{intentName}

Get an intent definition by name.

**Auth**: Admin required

**Response `200 OK`**: Single `IntentResponse` object

**Response codes**: `200` success | `403` admin required | `404` not found

---

### POST /api/intents

Create a new intent definition.

**Auth**: Admin required

**Request body**:
```json
{
  "name": "order_inquiry",
  "description": "User is asking about an existing order",
  "examples": ["Where is my order?", "Track my package"],
  "keywords": ["order", "track"],
  "profile": {
    "model": "gemini",
    "temperature": 0.2,
    "maxToolCalls": 5,
    "allowedTools": ["order_lookup"],
    "systemPrompt": "You are an order support agent.",
    "responseFormat": "TEXT"
  },
  "enabled": true
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `name` | string | Yes | — | Non-blank, unique |
| `description` | string | Yes | — | Non-blank |
| `examples` | array of string | No | `[]` | — |
| `keywords` | array of string | No | `[]` | — |
| `profile` | object | No | defaults | See profile fields below |
| `enabled` | boolean | No | `true` | — |

**Profile fields** (all nullable — `null` means use global default):

| Field | Type | Description |
|-------|------|-------------|
| `model` | string | LLM provider override (e.g. `"gemini"`, `"openai"`, `"anthropic"`) |
| `temperature` | number | Temperature override |
| `maxToolCalls` | integer | Max tool calls override |
| `allowedTools` | array of string | Tool whitelist (`null` = all tools allowed) |
| `systemPrompt` | string | System prompt override |
| `responseFormat` | string | `TEXT`, `JSON`, or `YAML` |

**Response `201 Created`**: `IntentResponse` object

**Response codes**: `201` created | `400` validation error | `403` admin required | `409` intent name already exists

---

### PUT /api/intents/{intentName}

Update an existing intent definition. Partial update — only provided fields are changed.

**Auth**: Admin required

**Request body** (all optional):
```json
{
  "description": "Updated description",
  "examples": ["New example"],
  "keywords": ["updated"],
  "profile": { "temperature": 0.5 },
  "enabled": false
}
```

**Response `200 OK`**: Updated `IntentResponse` object

**Response codes**: `200` success | `403` admin required | `404` not found

---

### DELETE /api/intents/{intentName}

Delete an intent definition. Idempotent.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required

---

## Human-in-the-Loop (HITL) Approvals

Base path: `/api/approvals`

> **Condition**: Only registered when `arc.reactor.approval.enabled=true`.

Manage pending tool call approval requests. Admins can approve (with optional argument modification) or reject pending calls.

### GET /api/approvals

List pending approval requests. Admins see all pending items; regular users see only their own.

**Auth**: Authenticated (admin sees all; user sees own)

**Response `200 OK`**: Array of pending approval objects (shape depends on `PendingApprovalStore` implementation)

**Response codes**: `200` success | `403` unauthenticated

---

### POST /api/approvals/{id}/approve

Approve a pending tool call. Optionally override the tool arguments.

**Auth**: Admin required

**Request body** (optional):
```json
{
  "modifiedArguments": {
    "issueId": "PROJ-123"
  }
}
```

If `modifiedArguments` is provided, the tool call will execute with those arguments instead of the original ones.

**Response `200 OK`**:
```json
{
  "success": true,
  "message": "Approved"
}
```

If the approval ID does not exist or was already resolved:
```json
{
  "success": false,
  "message": "Approval not found or already resolved"
}
```

**Response codes**: `200` always returned | `403` admin required

---

### POST /api/approvals/{id}/reject

Reject a pending tool call.

**Auth**: Admin required

**Request body** (optional):
```json
{
  "reason": "Rejected due to security policy"
}
```

**Response `200 OK`**:
```json
{
  "success": true,
  "message": "Rejected"
}
```

**Response codes**: `200` always returned | `403` admin required

---

## Scheduler (Cron Jobs)

Base path: `/api/scheduler/jobs`

> **Condition**: Only registered when a `DynamicSchedulerService` bean is present.

Manage scheduled MCP tool executions using cron expressions.

### GET /api/scheduler/jobs

List all scheduled jobs.

**Auth**: Admin required

**Response `200 OK`**:
```json
[
  {
    "id": "uuid",
    "name": "Daily Jira Sync",
    "description": "Sync Jira issues every morning",
    "cronExpression": "0 9 * * MON-FRI",
    "timezone": "Asia/Seoul",
    "mcpServerName": "jira-server",
    "toolName": "jira_sync_issues",
    "toolArguments": { "projectKey": "PROJ" },
    "slackChannelId": "C0123456",
    "enabled": true,
    "lastRunAt": null,
    "lastStatus": null,
    "lastResult": null,
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
]
```

**Response codes**: `200` success | `403` admin required

---

### POST /api/scheduler/jobs

Create a new scheduled job.

**Auth**: Admin required

**Request body**:
```json
{
  "name": "Daily Jira Sync",
  "description": "Sync Jira issues every morning",
  "cronExpression": "0 9 * * MON-FRI",
  "timezone": "Asia/Seoul",
  "mcpServerName": "jira-server",
  "toolName": "jira_sync_issues",
  "toolArguments": { "projectKey": "PROJ" },
  "slackChannelId": "C0123456",
  "enabled": true
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `name` | string | Yes | — | Non-blank, max 200 chars |
| `description` | string | No | `null` | — |
| `cronExpression` | string | Yes | — | Non-blank, valid cron |
| `timezone` | string | No | `"Asia/Seoul"` | Valid timezone ID |
| `mcpServerName` | string | Yes | — | Non-blank, must be a registered MCP server |
| `toolName` | string | Yes | — | Non-blank |
| `toolArguments` | object | No | `{}` | Passed to the tool |
| `slackChannelId` | string | No | `null` | Slack channel to post results to |
| `enabled` | boolean | No | `true` | — |

**Response `201 Created`**: `ScheduledJobResponse` object

**Response codes**: `201` created | `400` validation error | `403` admin required

---

### GET /api/scheduler/jobs/{id}

Get scheduled job details.

**Auth**: Admin required

**Response `200 OK`**: `ScheduledJobResponse` object

**Response codes**: `200` success | `403` admin required | `404` not found

---

### PUT /api/scheduler/jobs/{id}

Update a scheduled job. All fields must be provided (full replacement).

**Auth**: Admin required

**Request body**: Same fields as `POST /api/scheduler/jobs` (all required except `description`, `toolArguments`, `slackChannelId`)

**Response `200 OK`**: Updated `ScheduledJobResponse` object

**Response codes**: `200` success | `400` validation error | `403` admin required | `404` not found

---

### DELETE /api/scheduler/jobs/{id}

Delete a scheduled job.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required | `404` not found

---

### POST /api/scheduler/jobs/{id}/trigger

Trigger immediate execution of a scheduled job, bypassing the cron schedule.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**:
```json
{
  "result": "triggered"
}
```

**Response codes**: `200` success | `403` admin required | `404` not found

---

## RAG / Document Management

Base path: `/api/documents`

> **Condition**: Only registered when `arc.reactor.rag.enabled=true` and a `VectorStore` bean is available.

Manage documents in the vector store (RAG knowledge base). Documents are embedded and stored for similarity search.

### POST /api/documents

Add a single document to the vector store.

**Auth**: Admin required

**Request body**:
```json
{
  "content": "The return policy allows returns within 30 days...",
  "metadata": {
    "source": "policy-doc",
    "category": "returns"
  }
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `content` | string | Yes | Non-blank, max 100000 chars |
| `metadata` | object | No | Max 50 entries |

**Response `201 Created`**:
```json
{
  "id": "uuid",
  "content": "The return policy...",
  "metadata": { "source": "policy-doc", "category": "returns" }
}
```

**Response codes**: `201` created | `400` validation error | `403` admin required

---

### POST /api/documents/batch

Add multiple documents at once.

**Auth**: Admin required

**Request body**:
```json
{
  "documents": [
    { "content": "Document one content", "metadata": { "tag": "v1" } },
    { "content": "Document two content" }
  ]
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `documents` | array | Yes | Non-empty, max 100 items |
| `documents[].content` | string | Yes | Non-blank, max 100000 chars |
| `documents[].metadata` | object | No | Max 50 entries |

**Response `201 Created`**:
```json
{
  "count": 2,
  "ids": ["uuid-1", "uuid-2"]
}
```

**Response codes**: `201` created | `400` validation error | `403` admin required

---

### POST /api/documents/search

Search documents by semantic similarity.

**Auth**: Public (no admin check)

**Request body**:
```json
{
  "query": "What is the return policy?",
  "topK": 5,
  "similarityThreshold": 0.7
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `query` | string | Yes | — | Non-blank, max 10000 chars |
| `topK` | integer | No | `5` | — |
| `similarityThreshold` | number | No | `0.0` | — |

**Response `200 OK`**:
```json
[
  {
    "id": "uuid",
    "content": "The return policy allows returns within 30 days...",
    "metadata": { "source": "policy-doc" },
    "score": 0.92
  }
]
```

**Response codes**: `200` success | `400` validation error

---

### DELETE /api/documents

Delete documents by IDs.

**Auth**: Admin required

**Request body**:
```json
{
  "ids": ["uuid-1", "uuid-2"]
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `ids` | array of string | Yes | Non-empty, max 100 items |

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required

---

## RAG Ingestion Policy

Base path: `/api/rag-ingestion/policy`

> **Condition**: Only registered when `arc.reactor.rag.ingestion.dynamic.enabled=true`.

Controls the automatic capture of agent conversations as RAG ingestion candidates.

### GET /api/rag-ingestion/policy

Get the current RAG ingestion policy (effective in-use policy and stored database policy).

**Auth**: Admin required

**Response `200 OK`**:
```json
{
  "configEnabled": false,
  "dynamicEnabled": true,
  "effective": {
    "enabled": true,
    "requireReview": true,
    "allowedChannels": ["web", "api"],
    "minQueryChars": 10,
    "minResponseChars": 20,
    "blockedPatterns": [],
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  },
  "stored": {
    "enabled": true,
    "requireReview": true,
    "allowedChannels": ["web", "api"],
    "minQueryChars": 10,
    "minResponseChars": 20,
    "blockedPatterns": [],
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
}
```

**Response codes**: `200` success | `403` admin required

---

### PUT /api/rag-ingestion/policy

Update the stored RAG ingestion policy. Takes effect immediately.

**Auth**: Admin required

**Request body**:
```json
{
  "enabled": true,
  "requireReview": true,
  "allowedChannels": ["web", "slack"],
  "minQueryChars": 10,
  "minResponseChars": 20,
  "blockedPatterns": ["password", "secret"]
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `enabled` | boolean | No | `false` | — |
| `requireReview` | boolean | No | `true` | When `true`, candidates must be approved before ingestion |
| `allowedChannels` | array of string | No | `[]` | Max 300 entries. Stored lowercase |
| `minQueryChars` | integer | No | `10` | Minimum 1 |
| `minResponseChars` | integer | No | `20` | Minimum 1 |
| `blockedPatterns` | array of string | No | `[]` | Max 200 entries. Regex patterns to exclude |

**Response `200 OK`**: `RagIngestionPolicyResponse` object (the saved policy)

**Response codes**: `200` success | `400` validation error | `403` admin required

---

### DELETE /api/rag-ingestion/policy

Delete the stored RAG ingestion policy, reverting to config-file defaults.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required

---

## RAG Ingestion Candidates

Base path: `/api/rag-ingestion/candidates`

> **Condition**: Only registered when `arc.reactor.rag.ingestion.enabled=true`.

Review captured conversation candidates before they are ingested into the vector store.

Candidate statuses: `PENDING`, `INGESTED`, `REJECTED`

### GET /api/rag-ingestion/candidates

List RAG ingestion candidates.

**Auth**: Admin required

**Query parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `status` | string | No | — | Filter by status: `PENDING`, `INGESTED`, or `REJECTED` |
| `channel` | string | No | — | Filter by channel |
| `limit` | integer | No | `100` | Min 1, max 500 |

**Response `200 OK`**:
```json
[
  {
    "id": "uuid",
    "runId": "run-uuid",
    "userId": "user123",
    "sessionId": "session-uuid",
    "channel": "web",
    "query": "What is the return policy?",
    "response": "Our return policy allows...",
    "status": "PENDING",
    "capturedAt": 1700000000000,
    "reviewedAt": null,
    "reviewedBy": null,
    "reviewComment": null,
    "ingestedDocumentId": null
  }
]
```

**Response codes**: `200` success | `403` admin required

---

### POST /api/rag-ingestion/candidates/{id}/approve

Approve a candidate and ingest it into the VectorStore.

**Auth**: Admin required

**Request body** (optional):
```json
{
  "comment": "Good quality Q&A about return policy"
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `comment` | string | No | Max 500 chars |

**Response `200 OK`**: Updated `RagIngestionCandidateResponse` object with `status: "INGESTED"`

**Response codes**: `200` success | `403` admin required | `404` candidate not found | `409` already reviewed | `503` VectorStore not configured

---

### POST /api/rag-ingestion/candidates/{id}/reject

Reject a candidate.

**Auth**: Admin required

**Request body** (optional):
```json
{
  "comment": "Response quality is insufficient"
}
```

**Response `200 OK`**: Updated `RagIngestionCandidateResponse` object with `status: "REJECTED"`

**Response codes**: `200` success | `403` admin required | `404` candidate not found | `409` already reviewed

---

## Prompt Lab

Base path: `/api/prompt-lab`

> **Condition**: Only registered when an `ExperimentStore` bean is available.

Prompt optimization experiments. Run controlled A/B tests between prompt template versions using LLM-based evaluation.

Experiment statuses: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`

### POST /api/prompt-lab/experiments

Create a new experiment.

**Auth**: Admin required

**Request body**:
```json
{
  "name": "Support Bot Tone Experiment",
  "description": "Testing empathetic vs professional tone",
  "templateId": "template-uuid",
  "baselineVersionId": "version-uuid-1",
  "candidateVersionIds": ["version-uuid-2", "version-uuid-3"],
  "testQueries": [
    {
      "query": "I need help with my order",
      "intent": "order_inquiry",
      "domain": "support",
      "expectedBehavior": "Should ask for order ID",
      "tags": ["order"]
    }
  ],
  "evaluationConfig": {
    "structuralEnabled": true,
    "rulesEnabled": true,
    "llmJudgeEnabled": true,
    "llmJudgeBudgetTokens": 100000,
    "customRubric": null
  },
  "model": null,
  "judgeModel": null,
  "temperature": 0.3,
  "repetitions": 1
}
```

| Field | Type | Required | Default | Constraints |
|-------|------|----------|---------|-------------|
| `name` | string | Yes | — | Non-blank |
| `description` | string | No | `""` | — |
| `templateId` | string | Yes | — | Non-blank |
| `baselineVersionId` | string | Yes | — | Non-blank |
| `candidateVersionIds` | array of string | Yes | — | Non-empty. Total versions (1 + candidates) must not exceed `maxVersionsPerExperiment` |
| `testQueries` | array | Yes | — | Non-empty. Must not exceed `maxQueriesPerExperiment` |
| `evaluationConfig` | object | No | defaults | — |
| `model` | string | No | `null` | — |
| `judgeModel` | string | No | `null` | — |
| `temperature` | number | No | `0.3` | — |
| `repetitions` | integer | No | `1` | Must not exceed `maxRepetitions` |

**Response `201 Created`**: `ExperimentResponse` object

**Response codes**: `201` created | `400` limits exceeded or validation error | `403` admin required

---

### GET /api/prompt-lab/experiments

List experiments with optional filters.

**Auth**: Admin required

**Query parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `status` | string | No | Filter by status: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` |
| `templateId` | string | No | Filter by template ID |

**Response `200 OK`**: Array of `ExperimentResponse` objects

**Response codes**: `200` success | `403` admin required

---

### GET /api/prompt-lab/experiments/{id}

Get experiment details.

**Auth**: Admin required

**Response `200 OK`**:
```json
{
  "id": "uuid",
  "name": "Support Bot Tone Experiment",
  "description": "Testing empathetic vs professional tone",
  "templateId": "template-uuid",
  "baselineVersionId": "version-uuid-1",
  "candidateVersionIds": ["version-uuid-2"],
  "status": "COMPLETED",
  "autoGenerated": false,
  "createdBy": "admin-user",
  "createdAt": 1700000000000,
  "startedAt": 1700001000000,
  "completedAt": 1700002000000
}
```

**Response codes**: `200` success | `403` admin required | `404` not found

---

### POST /api/prompt-lab/experiments/{id}/run

Run an experiment asynchronously. The experiment must be in `PENDING` status.

**Auth**: Admin required

**Request body**: None

**Response `202 Accepted`**:
```json
{
  "status": "RUNNING",
  "experimentId": "uuid"
}
```

**Response codes**: `202` started | `400` not in PENDING state | `403` admin required | `404` not found | `429` max concurrent experiments reached

---

### POST /api/prompt-lab/experiments/{id}/cancel

Cancel a running experiment.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**: Updated `ExperimentResponse` with `status: "CANCELLED"`

**Response codes**: `200` success | `400` not in RUNNING state | `403` admin required | `404` not found

---

### GET /api/prompt-lab/experiments/{id}/status

Get the current execution status of an experiment.

**Auth**: Admin required

**Response `200 OK`**:
```json
{
  "experimentId": "uuid",
  "status": "RUNNING",
  "startedAt": 1700001000000,
  "completedAt": null,
  "errorMessage": null
}
```

**Response codes**: `200` success | `403` admin required | `404` not found

---

### GET /api/prompt-lab/experiments/{id}/trials

Get trial-level execution data for an experiment.

**Auth**: Admin required

**Response `200 OK`**: Array of `TrialResponse` objects:
```json
[
  {
    "id": "uuid",
    "promptVersionId": "version-uuid",
    "promptVersionNumber": 2,
    "query": "I need help with my order",
    "response": "Of course! Could you please provide your order ID?",
    "success": true,
    "score": 0.85,
    "durationMs": 1234,
    "toolsUsed": [],
    "passed": true,
    "executedAt": 1700001500000
  }
]
```

**Response codes**: `200` success | `403` admin required

---

### GET /api/prompt-lab/experiments/{id}/report

Get the analysis report for a completed experiment.

**Auth**: Admin required

**Response `200 OK`**: `ReportResponse` object containing version summaries and recommendation

**Response codes**: `200` success | `403` admin required | `404` report not yet available

---

### DELETE /api/prompt-lab/experiments/{id}

Delete an experiment and its associated data.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required

---

### POST /api/prompt-lab/experiments/{id}/activate

Activate the recommended version from a completed experiment's report.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**:
```json
{
  "activated": true,
  "templateId": "template-uuid",
  "versionId": "version-uuid",
  "versionNumber": 3
}
```

**Response codes**: `200` success | `400` no report or version activation failed | `403` admin required | `404` experiment not found

---

### POST /api/prompt-lab/auto-optimize

Run the full auto-optimization pipeline for a template: analyze feedback, generate candidate prompt versions, run experiments, and return results.

**Auth**: Admin required

**Request body**:
```json
{
  "templateId": "template-uuid",
  "candidateCount": 3,
  "judgeModel": null
}
```

**Response `202 Accepted`**:
```json
{
  "status": "STARTED",
  "templateId": "template-uuid",
  "jobId": "auto-template-uuid-1700000000000"
}
```

**Response codes**: `202` started | `400` validation error | `403` admin required

---

### POST /api/prompt-lab/analyze

Analyze feedback for a template to identify weaknesses in the current prompt.

**Auth**: Admin required

**Request body**:
```json
{
  "templateId": "template-uuid",
  "maxSamples": 50
}
```

**Response `200 OK`**:
```json
{
  "totalFeedback": 120,
  "negativeCount": 34,
  "weaknesses": [
    {
      "category": "tone",
      "description": "Responses are too formal for casual queries",
      "frequency": 18,
      "exampleQueries": ["Hey, can you help me?", "What's up with my order?"]
    }
  ],
  "sampleQueryCount": 50,
  "analyzedAt": 1700000000000
}
```

**Response codes**: `200` success | `400` validation error | `403` admin required

---

## Audit Log

Base path: `/api/admin/audits`

Unified audit log for all admin actions across the system.

### GET /api/admin/audits

List admin audit logs.

**Auth**: Admin required

**Query parameters**:

| Parameter | Type | Required | Default | Constraints |
|-----------|------|----------|---------|-------------|
| `limit` | integer | No | `100` | Min 1, max 1000 |
| `category` | string | No | — | Filter by category (e.g. `mcp_server`, `tool_policy`, `rag_ingestion_policy`) |
| `action` | string | No | — | Filter by action (e.g. `CREATE`, `UPDATE`, `DELETE`) |

**Response `200 OK`**:
```json
[
  {
    "id": "uuid",
    "category": "mcp_server",
    "action": "CREATE",
    "actor": "admin-user-id",
    "resourceType": "mcp_server",
    "resourceId": "my-mcp-server",
    "detail": "transport=SSE, autoConnect=true",
    "createdAt": 1700000000000
  }
]
```

**Known categories**: `mcp_server`, `mcp_access_policy`, `tool_policy`, `rag_ingestion_policy`, `rag_ingestion_candidate`, `output_guard_rule`

**Response codes**: `200` success | `403` admin required

---

## Ops Dashboard

Base path: `/api/ops`

Operational dashboard providing MCP status summary and Micrometer metrics.

### GET /api/ops/dashboard

Get an operational snapshot including MCP server status and selected metrics.

**Auth**: Admin required

**Query parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `names` | array of string | No | Metric names to include. Defaults to a built-in set of Arc Reactor and Slack metrics |

Default metrics: `arc.agent.executions`, `arc.agent.errors`, `arc.agent.tool.calls`, `arc.slack.inbound.total`, `arc.slack.duplicate.total`, `arc.slack.dropped.total`, `arc.slack.handler.duration`, `arc.slack.api.duration`, `arc.slack.api.retry.total`

**Response `200 OK`**:
```json
{
  "generatedAt": 1700000000000,
  "ragEnabled": false,
  "mcp": {
    "total": 3,
    "statusCounts": {
      "CONNECTED": 2,
      "FAILED": 1
    }
  },
  "metrics": [
    {
      "name": "arc.agent.executions",
      "meterCount": 1,
      "measurements": {
        "count": 42.0
      }
    }
  ]
}
```

**Response codes**: `200` success | `403` admin required

---

### GET /api/ops/metrics/names

List all available metric names registered in Micrometer.

**Auth**: Admin required

**Response `200 OK`**: Array of metric name strings (filtered to `arc.*`, `jvm.*`, `process.*`, `system.*`)

**Response codes**: `200` success | `403` admin required

---

## Feedback

Base path: `/api/feedback`

> **Condition**: Only registered when a `FeedbackStore` bean is available.

Collect and analyze user feedback on agent responses.

Rating values: `POSITIVE`, `NEGATIVE`, `NEUTRAL`

### POST /api/feedback

Submit feedback on an agent response. If `runId` is provided, the system automatically enriches the feedback with metadata from that run (query, response, session, tools used, duration).

**Auth**: Public (no auth check)

**Request body**:
```json
{
  "rating": "POSITIVE",
  "query": "What is the return policy?",
  "response": "Our return policy allows 30-day returns...",
  "comment": "Very helpful!",
  "sessionId": "session-uuid",
  "runId": "run-uuid",
  "userId": "user123",
  "intent": "policy_inquiry",
  "domain": "support",
  "model": "gemini-2.0-flash",
  "promptVersion": 3,
  "toolsUsed": ["search_docs"],
  "durationMs": 1234,
  "tags": ["quick-response"],
  "templateId": "template-uuid"
}
```

Only `rating` is required. All other fields are optional and will be auto-populated from run metadata when `runId` is provided.

**Response `201 Created`**: `FeedbackResponse` object

**Response codes**: `201` created | `400` invalid rating value

---

### GET /api/feedback

List feedback with optional filters.

**Auth**: Admin required

**Query parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `rating` | string | No | Filter by rating: `POSITIVE`, `NEGATIVE`, or `NEUTRAL` |
| `from` | string (ISO 8601) | No | Start of time range (e.g. `2026-01-01T00:00:00Z`) |
| `to` | string (ISO 8601) | No | End of time range |
| `intent` | string | No | Filter by intent |
| `sessionId` | string | No | Filter by session ID |
| `templateId` | string | No | Filter by template ID |

**Response `200 OK`**: Array of `FeedbackResponse` objects

**Response codes**: `200` success | `400` invalid filter parameters | `403` admin required

---

### GET /api/feedback/export

Export all feedback in eval-testing schema format.

**Auth**: Admin required

**Response `200 OK`**:
```json
{
  "version": 1,
  "exportedAt": "2026-02-28T12:00:00Z",
  "source": "arc-reactor",
  "items": [
    {
      "feedbackId": "uuid",
      "query": "...",
      "response": "...",
      "rating": "positive",
      "timestamp": "2026-02-28T11:00:00Z",
      "comment": null,
      "sessionId": null,
      "runId": null,
      "userId": null,
      "intent": null,
      "domain": null,
      "model": null,
      "promptVersion": null,
      "toolsUsed": null,
      "durationMs": null,
      "tags": null,
      "templateId": null
    }
  ]
}
```

**Response codes**: `200` success | `403` admin required

---

### GET /api/feedback/{feedbackId}

Get a single feedback entry by ID.

**Auth**: Public (no auth check)

**Response `200 OK`**: `FeedbackResponse` object

**Response codes**: `200` success | `404` not found

---

### DELETE /api/feedback/{feedbackId}

Delete a feedback entry.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required

---

## Admin Module (arc-admin)

The `arc-admin` module provides platform-level administration and multi-tenant management. All endpoints require `arc.reactor.admin.enabled=true` and a `DataSource` bean.

### Tenant identification

Endpoints in `TenantAdminController` resolve the tenant from the `X-Tenant-Id` request header. The header value must match a registered tenant ID.

---

### Metric Ingestion

Base path: `/api/admin/metrics/ingest`

> **Condition**: `arc.reactor.admin.enabled=true`

Accepts metric events from MCP servers and external sources.

#### POST /api/admin/metrics/ingest/mcp-health

Ingest a single MCP server health event.

**Auth**: Admin required

**Request body**:
```json
{
  "tenantId": "tenant-abc",
  "serverName": "jira-server",
  "status": "CONNECTED",
  "responseTimeMs": 120,
  "errorClass": null,
  "errorMessage": null,
  "toolCount": 12
}
```

**Response `202 Accepted`**: `{ "status": "accepted" }`

**Response codes**: `202` accepted | `403` admin required | `503` buffer full

---

#### POST /api/admin/metrics/ingest/tool-call

Ingest a single tool call event.

**Auth**: Admin required

**Request body**:
```json
{
  "tenantId": "tenant-abc",
  "runId": "run-uuid",
  "toolName": "jira_create_issue",
  "toolSource": "mcp",
  "mcpServerName": "jira-server",
  "callIndex": 0,
  "success": true,
  "durationMs": 340,
  "errorClass": null,
  "errorMessage": null
}
```

**Response `202 Accepted`**: `{ "status": "accepted" }`

**Response codes**: `202` accepted | `403` admin required | `503` buffer full

---

#### POST /api/admin/metrics/ingest/eval-result

Ingest a single eval result event.

**Auth**: Admin required

**Request body**:
```json
{
  "tenantId": "tenant-abc",
  "evalRunId": "eval-run-uuid",
  "testCaseId": "tc-001",
  "pass": true,
  "score": 0.87,
  "latencyMs": 1200,
  "tokenUsage": 800,
  "cost": "0.001200",
  "assertionType": "llm_judge",
  "failureClass": null,
  "failureDetail": null,
  "tags": ["regression", "v2"]
}
```

**Response `202 Accepted`**: `{ "status": "accepted" }`

**Response codes**: `202` accepted | `403` admin required | `503` buffer full

---

#### POST /api/admin/metrics/ingest/eval-results

Batch ingest eval results from a single eval run. Maximum 1000 items per batch.

**Auth**: Admin required

**Request body**:
```json
{
  "tenantId": "tenant-abc",
  "evalRunId": "eval-run-uuid",
  "results": [
    {
      "testCaseId": "tc-001",
      "pass": true,
      "score": 0.87,
      "latencyMs": 1200,
      "tokenUsage": 800,
      "cost": "0.001200",
      "assertionType": "llm_judge",
      "failureClass": null,
      "failureDetail": null,
      "tags": ["regression"]
    }
  ]
}
```

**Response `200 OK`**:
```json
{
  "evalRunId": "eval-run-uuid",
  "accepted": 1,
  "dropped": 0
}
```

**Response codes**: `200` summary | `400` empty list or batch size exceeded | `403` admin required

---

#### POST /api/admin/metrics/ingest/batch

Batch ingest MCP health events. Maximum 1000 items per batch.

**Auth**: Admin required

**Request body**: Array of `McpHealthRequest` objects (same shape as single ingest)

**Response `200 OK`**:
```json
{
  "accepted": 10,
  "dropped": 0
}
```

**Response codes**: `200` summary | `400` batch size exceeded | `403` admin required

---

### Platform Admin

Base path: `/api/admin/platform`

> **Condition**: `arc.reactor.admin.enabled=true` + DataSource

Platform-wide administration: tenant management, pricing, alerts.

#### GET /api/admin/platform/health

Get the platform health dashboard.

**Auth**: Admin required

**Response `200 OK`**: Platform health dashboard object (buffer usage, drop rate, write latency, active alert count)

**Response codes**: `200` success | `403` admin required

---

#### GET /api/admin/platform/tenants

List all tenants.

**Auth**: Admin required

**Response `200 OK`**: Array of `Tenant` objects

**Response codes**: `200` success | `403` admin required

---

#### GET /api/admin/platform/tenants/{id}

Get a tenant by ID.

**Auth**: Admin required

**Response `200 OK`**: `Tenant` object

**Response codes**: `200` success | `403` admin required | `404` not found

---

#### POST /api/admin/platform/tenants

Create a new tenant.

**Auth**: Admin required

**Request body**:
```json
{
  "name": "Acme Corp",
  "slug": "acme-corp",
  "plan": "FREE"
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `name` | string | Yes | Non-blank, max 200 chars |
| `slug` | string | Yes | 2-50 chars, pattern `^[a-z0-9][a-z0-9-]*[a-z0-9]$` |
| `plan` | string | No | Default `"FREE"`. Values: `FREE`, `PRO`, `ENTERPRISE` (case-insensitive) |

**Response `201 Created`**: `Tenant` object

**Response codes**: `201` created | `400` invalid plan or duplicate slug | `403` admin required

---

#### POST /api/admin/platform/tenants/{id}/suspend

Suspend a tenant.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**: Updated `Tenant` object

**Response codes**: `200` success | `403` admin required | `404` not found

---

#### POST /api/admin/platform/tenants/{id}/activate

Activate a suspended tenant.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**: Updated `Tenant` object

**Response codes**: `200` success | `403` admin required | `404` not found

---

#### GET /api/admin/platform/tenants/analytics

Get analytics summary for all tenants (current month usage and quota).

**Auth**: Admin required

**Response `200 OK`**: Array of `TenantAnalyticsSummary` objects

**Response codes**: `200` success | `403` admin required

---

#### GET /api/admin/platform/pricing

List all model pricing entries.

**Auth**: Admin required

**Response `200 OK`**: Array of `ModelPricing` objects

**Response codes**: `200` success | `403` admin required

---

#### POST /api/admin/platform/pricing

Create or update a model pricing entry (upsert by model ID).

**Auth**: Admin required

**Request body**: `ModelPricing` object (structure defined by `arc-admin` module)

**Response `200 OK`**: Saved `ModelPricing` object

**Response codes**: `200` success | `403` admin required

---

#### GET /api/admin/platform/alerts/rules

List all alert rules.

**Auth**: Admin required

**Response `200 OK`**: Array of `AlertRule` objects

**Response codes**: `200` success | `403` admin required

---

#### POST /api/admin/platform/alerts/rules

Create or update an alert rule (upsert).

**Auth**: Admin required

**Request body**: `AlertRule` object (structure defined by `arc-admin` module)

**Response `200 OK`**: Saved `AlertRule` object

**Response codes**: `200` success | `403` admin required

---

#### DELETE /api/admin/platform/alerts/rules/{id}

Delete an alert rule.

**Auth**: Admin required

**Response**: `204 No Content`

**Response codes**: `204` deleted | `403` admin required | `404` not found

---

#### GET /api/admin/platform/alerts

List all currently active alerts (platform-wide).

**Auth**: Admin required

**Response `200 OK`**: Array of active alert objects

**Response codes**: `200` success | `403` admin required

---

#### POST /api/admin/platform/alerts/{id}/resolve

Resolve an active alert.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**: Empty success response

**Response codes**: `200` success | `403` admin required

---

#### POST /api/admin/platform/alerts/evaluate

Trigger immediate alert rule evaluation across all tenants.

**Auth**: Admin required

**Request body**: None

**Response `200 OK`**:
```json
{
  "status": "evaluation complete"
}
```

**Response codes**: `200` success | `403` admin required

---

### Tenant Admin

Base path: `/api/admin/tenant`

> **Condition**: `arc.reactor.admin.enabled=true` + DataSource

Tenant-scoped dashboards and exports. Tenant is resolved from the `X-Tenant-Id` request header.

#### GET /api/admin/tenant/overview

Get the tenant overview dashboard.

**Auth**: Admin required

**Headers**: `X-Tenant-Id: <tenantId>`

**Response `200 OK`**: Tenant overview dashboard object

**Response codes**: `200` success | `403` admin required | `404` tenant not found

---

#### GET /api/admin/tenant/usage

Get tenant usage dashboard.

**Auth**: Admin required

**Headers**: `X-Tenant-Id: <tenantId>`

**Query parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fromMs` | long (epoch ms) | No | 30 days ago | Start of time range |
| `toMs` | long (epoch ms) | No | now | End of time range |

**Response `200 OK`**: Tenant usage dashboard object

**Response codes**: `200` success | `403` admin required

---

#### GET /api/admin/tenant/quality

Get tenant quality dashboard.

**Auth**: Admin required

**Headers**: `X-Tenant-Id: <tenantId>`

**Query parameters**: `fromMs`, `toMs` (same as usage)

**Response `200 OK`**: Tenant quality dashboard object

**Response codes**: `200` success | `403` admin required

---

#### GET /api/admin/tenant/tools

Get tenant tools dashboard.

**Auth**: Admin required

**Headers**: `X-Tenant-Id: <tenantId>`

**Query parameters**: `fromMs`, `toMs` (same as usage)

**Response `200 OK`**: Tenant tools dashboard object

**Response codes**: `200` success | `403` admin required

---

#### GET /api/admin/tenant/cost

Get tenant cost dashboard.

**Auth**: Admin required

**Headers**: `X-Tenant-Id: <tenantId>`

**Query parameters**: `fromMs`, `toMs` (same as usage)

**Response `200 OK`**: Tenant cost dashboard object

**Response codes**: `200` success | `403` admin required

---

#### GET /api/admin/tenant/slo

Get tenant SLO (Service Level Objective) status.

**Auth**: Admin required

**Headers**: `X-Tenant-Id: <tenantId>`

**Response `200 OK`**: SLO status object

**Response codes**: `200` success | `403` admin required | `404` tenant not found

---

#### GET /api/admin/tenant/alerts

List active alerts for the resolved tenant.

**Auth**: Admin required

**Headers**: `X-Tenant-Id: <tenantId>`

**Response `200 OK`**: Array of active alert objects for the tenant

**Response codes**: `200` success | `403` admin required

---

#### GET /api/admin/tenant/quota

Get the tenant's current month quota usage.

**Auth**: Admin required

**Headers**: `X-Tenant-Id: <tenantId>`

**Response `200 OK`**:
```json
{
  "quota": {
    "maxRequestsPerMonth": 10000,
    "maxTokensPerMonth": 5000000
  },
  "usage": {
    "requests": 3420,
    "tokens": 1230000,
    "costUsd": "1.234000"
  },
  "requestUsagePercent": 34.2,
  "tokenUsagePercent": 24.6
}
```

**Response codes**: `200` success | `403` admin required | `404` tenant not found

---

#### GET /api/admin/tenant/export/executions

Export executions as CSV.

**Auth**: Admin required

**Headers**: `X-Tenant-Id: <tenantId>`

**Query parameters**: `fromMs`, `toMs` (same as usage)

**Response `200 OK`**: CSV file (Content-Disposition: attachment; filename=executions.csv)

**Response codes**: `200` success | `403` admin required

---

#### GET /api/admin/tenant/export/tools

Export tool calls as CSV.

**Auth**: Admin required

**Headers**: `X-Tenant-Id: <tenantId>`

**Query parameters**: `fromMs`, `toMs` (same as usage)

**Response `200 OK`**: CSV file (Content-Disposition: attachment; filename=tool_calls.csv)

**Response codes**: `200` success | `403` admin required

---

## Error Response Reference

All error responses use the standard format:

```json
{
  "error": "Human-readable error description",
  "details": "Optional additional context",
  "timestamp": "2026-02-28T12:00:00Z"
}
```

| HTTP Status | When |
|-------------|------|
| `400 Bad Request` | Validation failure, invalid parameter value, constraint violation |
| `401 Unauthorized` | Missing or invalid JWT (auth enabled only) |
| `403 Forbidden` | Non-admin user attempting admin endpoint |
| `404 Not Found` | Resource does not exist |
| `409 Conflict` | Resource already exists (duplicate name or email) |
| `429 Too Many Requests` | Rate limit exceeded (login endpoint or concurrent experiment limit) |
| `503 Service Unavailable` | Dependent service unavailable (VectorStore not configured, metric buffer full) |
| `504 Gateway Timeout` | Upstream MCP admin API timed out |

`403` responses always include a body (`"error": "Admin access required"`). They are never empty.
