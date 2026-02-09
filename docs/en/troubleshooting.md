# Troubleshooting

Common issues, their causes, and solutions.

---

## MCP: "HTTP transport not supported"

**Symptom:** MCP server registered with `McpTransportType.HTTP` silently fails. Tools are not loaded.

**Cause:** MCP SDK 0.10.0 does not include the Streamable HTTP transport (`HttpClientStreamableHttpTransport`).

**Solution:** Use SSE transport instead for remote servers:

```kotlin
mcpManager.register(McpServer(
    name = "my-server",
    transportType = McpTransportType.SSE,  // Not HTTP
    config = mapOf("url" to "http://localhost:3001/sse")
))
```

See [MCP Integration Guide](mcp.md) for transport details.

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

**Symptom:** Application fails to start with `IllegalArgumentException` when auth is enabled.

**Cause:** The `arc.reactor.auth.jwt-secret` value is shorter than 32 bytes (required for HS256 HMAC).

**Solution:**

```bash
# Generate a secure 32-byte secret
openssl rand -base64 32

# Set as environment variable
export JWT_SECRET=$(openssl rand -base64 32)
```

```yaml
arc:
  reactor:
    auth:
      enabled: true
      jwt-secret: ${JWT_SECRET}
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
    mcp-security:
      max-tool-output-length: 100000  # Default: 50000
```

---

## Auth: Endpoints return 404 (not 401)

**Symptom:** `POST /api/auth/login` returns 404 Not Found instead of working.

**Cause:** Authentication is disabled by default. The `AuthController` bean is not registered unless explicitly enabled.

**Solution:**

1. Enable auth in configuration:
```yaml
arc:
  reactor:
    auth:
      enabled: true
      jwt-secret: ${JWT_SECRET}
```

2. Include auth dependencies at build time:
```bash
./gradlew bootRun -Pauth=true
```

Or switch from `compileOnly` to `implementation` in `build.gradle.kts` for JJWT and Spring Security Crypto.

---

## Sessions: Users can see each other's sessions

**Symptom:** All users share the same conversation history (no isolation).

**Cause:** When authentication is disabled, all requests use the `anonymous` userId. Sessions are not isolated.

**Solution:** Enable authentication. When JWT auth is active, each user gets a unique userId extracted from their token, and session access is automatically scoped to that user.

```yaml
arc:
  reactor:
    auth:
      enabled: true
      jwt-secret: ${JWT_SECRET}
```

---

## Prompt Templates: "Admin access required" (403)

**Symptom:** `POST /api/prompt-templates` returns 403 Forbidden.

**Cause:** Template create/update/delete operations require the `ADMIN` role.

**Solution:** The first admin user is auto-created on startup when auth is enabled. Check your logs for the admin credentials, or set them via environment variables:

```yaml
arc:
  reactor:
    auth:
      admin-email: admin@example.com
      admin-password: ${ADMIN_PASSWORD}
```

Users with `USER` role can still read templates (`GET` endpoints).

---

## Startup: "Failed to configure a DataSource"

**Symptom:** Application fails to start with a DataSource auto-configuration error.

**Cause:** Spring detects `spring.datasource.url` in your configuration but the JDBC driver is not on the classpath (it's `compileOnly` by default).

**Solution:**

```bash
# Include DB dependencies at runtime
./gradlew bootRun -Pdb=true
```

Or switch `spring-boot-starter-jdbc` and `postgresql` from `compileOnly` to `implementation` in `build.gradle.kts`.

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
