# Write Tool Policy (Read-Only vs Write)

Arc Reactor supports a simple safety policy for side-effecting ("write") tools.

## Why

In enterprise environments, tools like Jira/Confluence/Bitbucket can mutate state (create/update/delete/merge).
Even without per-employee permission differences, it is common to enforce an organization-wide policy:

- Web: allow write tools only via Human-in-the-Loop approval
- Slack: deny write tools (chat-first UX + higher risk of accidental destructive actions)

## How It Works

1. Requests include a `metadata.channel` value (e.g. `web`, `slack`).
2. The agent builds a `HookContext.channel` from that metadata.
3. A `BeforeToolCallHook` (`WriteToolBlockHook`) blocks configured write tools on configured channels.
4. When HITL is enabled, write tools are automatically included in the approval tool list.

### Core classes

**`ToolExecutionPolicyEngine`** is the central decision maker. It exposes two methods:

- `isWriteTool(toolName, arguments)` — returns true if the tool is considered a write tool under the current policy. Returns false when the policy is disabled or when the tool call is a read-only preview (see below).
- `evaluate(channel, toolName, arguments)` — returns `Allow` or `Deny(reason)`. The evaluation order is:
  1. If the policy is disabled or `writeToolNames` is empty, allow.
  2. If the channel is null/blank or not in `denyWriteChannels`, allow.
  3. If the tool is not a write tool, allow.
  4. If the tool is in `allowWriteToolNamesInDenyChannels` (global exception list), allow.
  5. If the tool is in `allowWriteToolNamesByChannel[channel]` (per-channel exception), allow.
  6. Otherwise, deny with `denyWriteMessage`.

**Read-only preview**: Certain tools support a `dryRun` or preview mode. When arguments indicate a dry run (e.g. `dryRun=true`), `isWriteTool()` returns false even if the tool name is in `writeToolNames`. This prevents blocking read-only previews of write tools.

**`DynamicToolApprovalPolicy`** bridges the tool policy with HITL approval. It implements `ToolApprovalPolicy` and combines:

- Static tool names from `arc.reactor.approval.tool-names` (always require approval)
- Dynamic write tool names from `ToolExecutionPolicyEngine.isWriteTool()` (require approval when the tool policy says they are write tools)

This means the HITL approval list automatically stays in sync with write tool policy changes, whether those changes come from config or the admin API.

## Configuration

```yaml
arc:
  reactor:
    approval:
      enabled: true

    tool-policy:
      enabled: true
      # Optional: DB-backed dynamic policy (admin-managed)
      # dynamic:
      #   enabled: true
      #   refresh-ms: 10000
      write-tool-names:
        - jira_create_issue
        - confluence_update_page
        - bitbucket_merge_pr
      deny-write-channels:          # default: ["slack"]
        - slack
      allow-write-tool-names-in-deny-channels:    # global exception list
        - jira_create_issue
      allow-write-tool-names-by-channel:           # per-channel exception map
        slack:
          - confluence_update_page
      deny-write-message: "Error: This tool is not allowed in this channel"
```

### Config field reference

| Field | Default | Description |
|-------|---------|-------------|
| `enabled` | `false` | Master switch for tool policy enforcement |
| `dynamic.enabled` | `false` | Enable DB-backed dynamic policy (admin API) |
| `dynamic.refresh-ms` | `10000` | Cache refresh interval for dynamic policy (ms) |
| `write-tool-names` | `[]` | Tool names considered side-effecting |
| `deny-write-channels` | `["slack"]` | Channels where write tools are blocked |
| `allow-write-tool-names-in-deny-channels` | `[]` | Write tools allowed even in denied channels (global) |
| `allow-write-tool-names-by-channel` | `{}` | Per-channel allowlist for specific write tools in denied channels |
| `deny-write-message` | `"Error: This tool is not allowed in this channel"` | Error message returned when a tool call is denied |
