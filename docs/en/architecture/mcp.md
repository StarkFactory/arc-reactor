# MCP Integration Guide (Overview)

This page is the entry point for Arc Reactor MCP documentation.

## What Changed

- Runtime update flow now synchronizes manager state explicitly.
- Connection paths are tuned for faster failure on invalid/unreachable endpoints.
- Auto-reconnect behavior remains fail-safe and bounded by config.
- Streamable HTTP transport is still not supported in the current MCP SDK in use.

## Read by Topic

- [Transports & Security](mcp/transports-and-security.md)
- [Runtime Registration & Admin APIs](mcp/runtime-management.md)
- [MCP Troubleshooting](mcp/troubleshooting.md)
- [Deep Dive (Full Legacy Document)](mcp/deep-dive.md)

## Related

- [Tools Reference](../reference/tools.md)
- [ReAct Loop](react-loop.md)
