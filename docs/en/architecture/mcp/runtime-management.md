# MCP Runtime Registration and Admin APIs

## Lifecycle Model

1. Register/update server definition in store
2. Synchronize runtime manager state
3. Connect/disconnect on demand or auto-connect policy
4. Expose connected tools into tool selection at request time

## Important Behavior

- Update flows must keep runtime manager state synchronized, not only persistence.
- Reconnect logic should not run with stale definitions.
- Admin write APIs must remain ownership/admin protected according to auth mode.

## Operational Checklist

- verify server status after create/update
- check tool list population for expected callbacks
- confirm reconnect backoff does not flood logs under repeated failures

## Related

- [MCP Overview](../mcp.md)
- [MCP Troubleshooting](troubleshooting.md)
