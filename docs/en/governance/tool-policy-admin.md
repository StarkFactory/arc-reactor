# Tool Policy Admin (Dynamic Write Tool Rules)

Arc Reactor can manage the "write tool" policy dynamically (DB-backed), so an admin can change allowed/denied tool behavior without redeploying.

## What It Controls

- `writeToolNames`: tool names that are considered side-effecting (create/update/delete/merge/deploy, etc.)
- `denyWriteChannels`: channels where write tools are **blocked** (fail-closed). Default: `["slack"]`.
- `allowWriteToolNamesInDenyChannels`: global exception list — write tool names that are allowed even in denied channels.
- `allowWriteToolNamesByChannel`: per-channel exception map — allows specific write tools for specific denied channels.
- `denyWriteMessage`: message returned to the user when blocked.

## How It Interacts With HITL

- Slack: write tools are blocked by a `BeforeToolCallHook` when `channel` is in `denyWriteChannels`.
- Web: when HITL is enabled, write tools are automatically treated as "approval required" tools.

## Enable

```yaml
arc:
  reactor:
    tool-policy:
      enabled: true
      dynamic:
        enabled: true
        refresh-ms: 10000

    approval:
      enabled: true   # required if you want write tools to require approval on web

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/arcreactor
    username: arc
    password: arc
  flyway:
    enabled: true
```

## API (ADMIN)

> **Conditional activation**: The `ToolPolicyController` is annotated with
> `@ConditionalOnProperty(prefix = "arc.reactor.tool-policy.dynamic", name = ["enabled"], havingValue = "true")`.
> The REST endpoints below are **only available** when `arc.reactor.tool-policy.dynamic.enabled=true`.
> If dynamic policy is not enabled, these endpoints will return 404.

- `GET /api/tool-policy` : returns effective + stored policy
- `PUT /api/tool-policy` : updates stored policy
- `DELETE /api/tool-policy` : deletes stored policy (resets to config defaults)

### GET response

The GET endpoint returns a `ToolPolicyStateResponse` with four fields:

| Field | Type | Description |
|-------|------|-------------|
| `configEnabled` | boolean | Whether `arc.reactor.tool-policy.enabled` is true in application config |
| `dynamicEnabled` | boolean | Whether `arc.reactor.tool-policy.dynamic.enabled` is true |
| `effective` | object | The currently active policy (merged from config + DB) |
| `stored` | object or null | The DB-stored policy, or null if no override has been saved |

### PUT example

```bash
curl -X PUT http://localhost:8080/api/tool-policy \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "writeToolNames": ["jira_create_issue", "bitbucket_merge_pr"],
    "denyWriteChannels": ["slack"],
    "allowWriteToolNamesInDenyChannels": ["jira_create_issue"],
    "allowWriteToolNamesByChannel": {
      "slack": ["confluence_update_page"]
    },
    "denyWriteMessage": "Write tools are disabled on Slack"
  }'
```

### Request validation limits

| Field | Max |
|-------|-----|
| `writeToolNames` | 500 entries |
| `denyWriteChannels` | 50 entries |
| `allowWriteToolNamesInDenyChannels` | 500 entries |
| `allowWriteToolNamesByChannel` | 200 channels |
| `denyWriteMessage` | 500 characters |
