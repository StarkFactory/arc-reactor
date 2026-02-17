# MCP Troubleshooting (Current)

## HTTP Transport Not Working

If server is configured as streamable HTTP and does not connect:

- use `SSE` transport instead
- current SDK path does not provide streamable HTTP transport support

## SSE URL Failures

Validate:

- absolute `http://` or `https://` URL
- reachable endpoint
- reasonable timeout configuration

## STDIO Command Not Found

Validate:

- binary path exists (especially when absolute path is configured)
- command is available in runtime PATH
- startup args are correct

## Slow Tests Around MCP

Typical causes:

- long connect/initialize timeout in tests
- enabled reconnect loops in negative tests

Recommended test setup:

- short connection timeout
- reconnect disabled unless that behavior is under test
- deterministic invalid endpoints for fast failure

## Next

- [Transports & Security](transports-and-security.md)
- [Runtime Management](runtime-management.md)
