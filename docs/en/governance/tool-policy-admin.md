# Tool Policy Admin (Dynamic Write Tool Rules)

Arc Reactor can manage the "write tool" policy dynamically (DB-backed), so an admin can change allowed/denied tool behavior without redeploying.

## What It Controls

- `writeToolNames`: tool names that are considered side-effecting (create/update/delete/merge/deploy, etc.)
- `denyWriteChannels`: channels where write tools are **blocked** (fail-closed). Example: `slack`.
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

- `GET /api/tool-policy` : returns effective + stored policy
- `PUT /api/tool-policy` : updates stored policy
- `DELETE /api/tool-policy` : deletes stored policy (resets to config defaults)

Example update:

```bash
curl -X PUT http://localhost:8080/api/tool-policy \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "writeToolNames": ["jira_create_issue", "bitbucket_merge_pr"],
    "denyWriteChannels": ["slack"],
    "denyWriteMessage": "Write tools are disabled on Slack"
  }'
```

