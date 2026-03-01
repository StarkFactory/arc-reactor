# Troubleshooting

Common issues, their causes, and solutions.

---

## MCP: "HTTP transport not supported"

**Symptom:** MCP server registered with `McpTransportType.HTTP` silently fails. Tools are not loaded.

**Cause:** In the current MCP SDK path (0.17.2), streamable HTTP transport is not available for this integration path.

**Solution:** Use SSE transport instead for remote servers:

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-server",
    "transportType": "SSE",
    "config": { "url": "http://localhost:3001/sse" },
    "autoConnect": true
  }'
```

See [MCP Integration Guide](../architecture/mcp.md) for transport details.

---

## MCP: STDIO server "command not found"

**Symptom:** `ProcessBuilder` error or empty tool list after connecting an MCP server via STDIO.

**Cause:** The MCP server command (usually `npx`) is not installed or not in the system PATH.

**Solution:**

```bash
# Verify Node.js and npx are available
node --version   # Should be 18+
npx --version

# Test the MCP server command directly
npx -y @modelcontextprotocol/server-filesystem /tmp
```

If running in Docker, ensure Node.js is installed in your image.

---

## Auth: "JWT secret must be at least 32 bytes"

**Symptom:** Application fails to start with `IllegalArgumentException` during auth initialization.

**Cause:** The `arc.reactor.auth.jwt-secret` value is shorter than 32 bytes (required for HS256 HMAC).

**Solution:**

```bash
# Generate a secure 32-byte secret
openssl rand -base64 32

# Set as environment variable
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
```

```yaml
arc:
  reactor:
    auth:
      jwt-secret: ${ARC_REACTOR_AUTH_JWT_SECRET}
```

---

## CORS: "No Access-Control-Allow-Origin" header

**Symptom:** Browser requests to the API are blocked with a CORS error.

**Cause:** CORS is opt-in and disabled by default.

**Solution:**

```yaml
arc:
  reactor:
    cors:
      enabled: true
      allowed-origins:
        - http://localhost:3000
        - https://your-domain.com
```

---

## Tool output appears truncated

**Symptom:** Tool results end with `[TRUNCATED: output exceeded max length]`.

**Cause:** MCP tool output exceeds the default limit of 50,000 characters (`McpSecurityConfig.maxToolOutputLength`).

**Solution:** Increase the limit in your configuration:

```yaml
arc:
  reactor:
    mcp:
      security:
        max-tool-output-length: 100000  # Default: 50000
```

---

## Auth: Endpoints return 404 (not 401)

**Symptom:** `POST /api/auth/login` returns 404 Not Found instead of working.

**Cause:** You are running an old/custom binary that does not include current auth components.

**Solution:**

1. Keep runtime auth secret configured:
```yaml
arc:
  reactor:
    auth:
      jwt-secret: ${ARC_REACTOR_AUTH_JWT_SECRET}
```

2. Rebuild with default settings and run the latest artifact:
```bash
./gradlew clean :arc-app:bootJar -Pdb=true
java -jar arc-app/build/libs/arc-app-*.jar
```

Auth dependencies are included by default in current builds.

---

## Startup: "arc.reactor.auth.enabled=false is no longer supported"

**Symptom:** Startup fails with a message that `arc.reactor.auth.enabled=false` is not supported.

**Cause:** Auth toggle was removed. Arc Reactor authentication is always required now.

**Solution:**

1. Remove `arc.reactor.auth.enabled` from all configs/env/Helm values.
2. Keep only:
```yaml
arc:
  reactor:
    auth:
      jwt-secret: ${ARC_REACTOR_AUTH_JWT_SECRET}
```
3. Set a valid default tenant if needed:
```yaml
arc:
  reactor:
    auth:
      default-tenant-id: default
```

---

## Startup: "spring.datasource.username/password is required"

**Symptom:** Startup fails in postgres-required mode with missing datasource username/password.

**Cause:** `arc.reactor.postgres.required=true` is active, but DB credentials are missing.

**Solution:**

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
export SPRING_DATASOURCE_USERNAME=arc
export SPRING_DATASOURCE_PASSWORD=arc
```

For local non-production runs only:

```bash
export ARC_REACTOR_POSTGRES_REQUIRED=false
```

---

## Startup: "Migration checksum mismatch for migration version <N>"

**Symptom:** Startup fails with Flyway validation errors such as checksum mismatch on an already
applied migration version.

**Cause:** A previously applied `V*.sql` migration file was modified/renamed/deleted, so Flyway
detected divergent schema history.

**Solution (preferred):**

1. Revert edits to the existing `V<version>__*.sql` file.
2. Add a new migration version for additional schema changes.
3. Redeploy.

**Emergency path:** If immediate recovery is required and revert is not possible, use controlled
`flyway repair` only with explicit approval and DB backup.

Detailed procedure: [Database Migration Runbook](../operations/database-migration-runbook.md)

---

## Sessions: Users can see each other's sessions

**Symptom:** All users share the same conversation history (no isolation).

**Cause:** Requests are made with the same user identity (for example same shared token),
or the client does not propagate per-user auth correctly.

**Solution:** Ensure each end user uses a distinct JWT identity. Session access is scoped by
`userId` extracted from token.

```yaml
arc:
  reactor:
    auth:
      jwt-secret: ${ARC_REACTOR_AUTH_JWT_SECRET}
```

---

## Prompt Templates: "Admin access required" (403)

**Symptom:** `POST /api/prompt-templates` returns 403 Forbidden.

**Cause:** Template create/update/delete operations require the `ADMIN` role.

**Solution:** The first admin user is auto-created on startup. Check your logs for the admin credentials, or set them via environment variables:

```bash
export ARC_REACTOR_AUTH_ADMIN_EMAIL=admin@example.com
export ARC_REACTOR_AUTH_ADMIN_PASSWORD='change-me-now'
export ARC_REACTOR_AUTH_ADMIN_NAME='Platform Admin'
```

Users with `USER` role can still read templates (`GET` endpoints).

---

## Startup: "Failed to configure a DataSource"

**Symptom:** Application fails to start with datasource or JDBC connection errors.

**Cause:** Datasource is required by default (`arc.reactor.postgres.required=true`) and one of the datasource values is missing/invalid.

**Solution:**

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
export SPRING_DATASOURCE_USERNAME=arc
export SPRING_DATASOURCE_PASSWORD=arc
```

For local non-production experiments only:

```bash
export ARC_REACTOR_POSTGRES_REQUIRED=false
```

---

## Agent: "No ChatClient bean found"

**Symptom:** Application fails to start or chat requests return errors because no LLM provider is configured.

**Cause:** No LLM provider API key is set. Spring AI needs at least one provider configured to create a `ChatClient` bean.

**Solution:** Set an API key for your chosen provider:

```bash
# Google Gemini (default)
export GEMINI_API_KEY=your-api-key

# OpenAI
export SPRING_AI_OPENAI_API_KEY=your-api-key

# Anthropic
export SPRING_AI_ANTHROPIC_API_KEY=your-api-key
```

Then enable the provider in `build.gradle.kts` (switch from `compileOnly` to `implementation`).
