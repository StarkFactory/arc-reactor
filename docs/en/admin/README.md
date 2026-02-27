# Admin Documentation

This section contains administration guides for Arc Reactor.

## Contents

- [API Reference](./api-reference.md) — Complete reference for every admin-accessible REST endpoint, including authentication setup, request/response schemas, and status codes.

## Quick Start

### Auth disabled (default)

All requests are treated as admin. No token is required.

```bash
curl http://localhost:8080/api/personas
```

### Auth enabled (production)

1. Login to get a token:
   ```bash
   curl -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"email":"admin@example.com","password":"yourpassword"}'
   ```

2. Use the token on subsequent requests:
   ```bash
   curl http://localhost:8080/api/personas \
     -H "Authorization: Bearer <token>"
   ```

## Key Admin Operations

| Operation | Endpoint |
|-----------|----------|
| Register an MCP server | `POST /api/mcp/servers` |
| Create a persona | `POST /api/personas` |
| Manage prompt templates | `POST /api/prompt-templates` |
| Add RAG documents | `POST /api/documents` |
| View audit logs | `GET /api/admin/audits` |
| Ops dashboard | `GET /api/ops/dashboard` |
| Platform health (arc-admin) | `GET /api/admin/platform/health` |
| Tenant dashboards (arc-admin) | `GET /api/admin/tenant/overview` |
