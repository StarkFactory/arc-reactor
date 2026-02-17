# MCP Transports and Security

## Supported Transports

- `STDIO`: local process transport
- `SSE`: remote HTTP Server-Sent Events transport
- `HTTP (streamable)`: not supported in current SDK path; use SSE instead

## Runtime Validation and Fast-Fail

Arc Reactor validates MCP configuration before full initialization where possible:

- invalid/missing URL for SSE is rejected early
- non-existing absolute command paths for STDIO are rejected early
- request and initialization timeouts are aligned to connection timeout settings

This prevents long test and startup stalls when a server definition is wrong.

## Security Notes

- Keep admin APIs protected when auth is enabled.
- Prefer explicit server allowlists for production.
- Treat MCP tool output as untrusted input and enforce output/tool policies.

## Next

- [Runtime Registration & Admin APIs](runtime-management.md)
- [Troubleshooting](troubleshooting.md)
