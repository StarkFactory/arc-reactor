# Arc Reactor Ecosystem — Runtime Setup

## Projects

| # | Project | Path | Port | Boot Command |
|---|---------|------|------|-------------|
| 1 | **arc-reactor** (backend) | `/Users/jinan/ai/arc-reactor` | 18081 | `SERVER_PORT=18081 ./gradlew bootRun --no-daemon` |
| 2 | **swagger-mcp-server** | `/Users/jinan/ai/swagger-mcp-server` | 8081 | `./gradlew bootRun --no-daemon` |
| 3 | **atlassian-mcp-server** | `/Users/jinan/ai/atlassian-mcp-server` | 8085 | `./gradlew bootRun --no-daemon` |
| 4 | **arc-reactor-admin** (frontend) | `/Users/jinan/ai/arc-reactor-admin` | 3001 | `pnpm dev` |

> **Port convention:** `SERVER_PORT=18081` is the ecosystem convention to avoid conflicts with other local services. The default Spring Boot port is 8080.

## Boot Order

1. **arc-reactor** — backend must be up first (admin proxies to it)
2. **swagger-mcp-server** + **atlassian-mcp-server** — MCP servers (register via REST after boot)
3. **arc-reactor-admin** — frontend (proxies API calls to arc-reactor on port 18081)

## Environment Variables (all set on this machine)

### arc-reactor
| Variable | Purpose |
|----------|---------|
| `GEMINI_API_KEY` | LLM provider (Gemini) |
| `ARC_REACTOR_AUTH_JWT_SECRET` | JWT signing secret (min 32 bytes) |
| `ARC_REACTOR_AUTH_ADMIN_EMAIL` | Admin login email |
| `ARC_REACTOR_AUTH_ADMIN_PASSWORD` | Admin login password |
| `SPRING_DATASOURCE_URL` | PostgreSQL URL (e.g. `jdbc:postgresql://localhost:5432/arcreactor`) |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL username (e.g. `arc`) |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password (e.g. `arc`) |
| `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES` | Comma-separated MCP server allowlist (e.g. `atlassian,swagger`) |
| `ARC_REACTOR_MCP_ALLOW_PRIVATE_ADDRESSES` | Set `true` to allow local SSE connections (default `false`) |
| `SLACK_BOT_TOKEN` | Slack bot integration |
| `SLACK_CHANNEL_ID` | Default Slack channel |
| `SLACK_APP_TOKEN` | Slack Socket Mode |

> **PostgreSQL required:** The app requires a running PostgreSQL instance for `bootRun`. Start one via `docker-compose up -d db` or set the `SPRING_DATASOURCE_*` variables to point at an existing instance.

> **MCP allowlist:** The default allowlist is `atlassian` only. To register the swagger MCP server, set `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES=atlassian,swagger` before boot.

> **Private addresses:** Local SSE MCP servers (localhost URLs) are blocked by the SSRF validator by default. Set `ARC_REACTOR_MCP_ALLOW_PRIVATE_ADDRESSES=true` for local development.

### swagger-mcp-server
| Variable | Purpose |
|----------|---------|
| (none required) | Runs standalone with H2 defaults |

### atlassian-mcp-server
| Variable | Purpose |
|----------|---------|
| `ATLASSIAN_BASE_URL` | Atlassian cloud base URL |
| `ATLASSIAN_USERNAME` | Atlassian username |
| `ATLASSIAN_CLOUD_ID` | Atlassian cloud instance ID |
| `JIRA_API_TOKEN` | Jira API token |
| `CONFLUENCE_API_TOKEN` | Confluence API token |
| `BITBUCKET_API_TOKEN` | Bitbucket API token |

### arc-reactor-admin
| Variable | Purpose |
|----------|---------|
| `VITE_PROXY_TARGET` | Backend URL (default: `http://localhost:18081`) |

## MCP Server Registration

MCP server endpoints require admin JWT authentication. Log in first, then use the token for registration.

### Step 1 — Get a JWT token

```bash
TOKEN=$(curl -s -X POST http://localhost:18081/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"$ARC_REACTOR_AUTH_ADMIN_EMAIL\", \"password\": \"$ARC_REACTOR_AUTH_ADMIN_PASSWORD\"}" \
  | jq -r '.token')
```

### Step 2 — Register MCP servers

```bash
# Register swagger-mcp-server
curl -X POST http://localhost:18081/api/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name": "swagger", "transportType": "SSE", "config": {"url": "http://localhost:8081/sse"}}'

# Register atlassian-mcp-server
curl -X POST http://localhost:18081/api/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name": "atlassian", "transportType": "SSE", "config": {"url": "http://localhost:8085/sse"}}'
```

## Health Check URLs

| Service | URL |
|---------|-----|
| arc-reactor | `http://localhost:18081/actuator/health` |
| swagger-mcp-server | `http://localhost:8081/actuator/health` |
| atlassian-mcp-server | `http://localhost:8085/actuator/health` |
| arc-reactor-admin | `http://localhost:3001` |

## E2E Tests (admin)

```bash
cd /Users/jinan/ai/arc-reactor-admin
pnpm test:e2e        # Playwright headless
pnpm test:e2e:ui     # Playwright with UI
```

Requires arc-reactor backend running on port 18081 (auto-starts dev server on port 3001).
