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
3. A `BeforeToolCallHook` blocks configured write tools on configured channels.
4. When HITL is enabled, write tools are automatically included in the approval tool list.

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
      deny-write-channels:
        - slack
```
