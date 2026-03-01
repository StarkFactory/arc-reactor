# Arc Reactor

[![CI](https://github.com/StarkFactory/arc-reactor/actions/workflows/ci.yml/badge.svg)](https://github.com/StarkFactory/arc-reactor/actions/workflows/ci.yml)
[![Version](https://img.shields.io/badge/version-4.5.0-blue.svg)](https://github.com/StarkFactory/arc-reactor/releases/tag/v4.5.0)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-purple.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-orange.svg)](https://spring.io/projects/spring-ai)

[한국어](README.ko.md)

**Enterprise-oriented AI Agent runtime built on Spring AI. Fork it, customize it, deploy it.**

## What is Arc Reactor?

Arc Reactor is an open-source project template for teams who need a production-ready AI Agent
platform with strong governance controls. Rather than a library you add to an existing project,
Arc Reactor is a full application you fork and operate as your own platform.

The runtime is built around a **ReAct loop** (Reasoning + Acting): the LLM decides which tools to
call, calls them through Arc Reactor's tool system, observes the results, and repeats until it
produces a final answer. Every step of that loop passes through configurable safety and governance
layers before it reaches users.

Arc Reactor solves the hard operational problems that appear after a proof-of-concept becomes a
production service: controlling which tools run on which channels, maintaining conversation history
across restarts, versioning prompts, auditing every action, approving high-risk tool invocations
before they execute, and integrating chat into Slack alongside the web API.

> Arc Reactor is **not** a drop-in library. The intended model is: **fork → customize → deploy**.
> Upstream maintainers are responsible for the upstream repository only. Downstream fork operators
> are solely responsible for their own deployments, security hardening, compliance, and incident
> response. No SLA, warranty, or indemnification is provided for downstream operations.

## Features

- **ReAct execution engine** — bounded tool-call iterations, configurable retry, automatic context
  trimming, and structured output (Text / JSON / YAML) with validation and auto-repair
- **5-stage Guard pipeline** — fail-close input validation: rate limiting, input length, Unicode
  normalization, classification (optional), and canary token detection
- **4-point Hook lifecycle** — BeforeStart, AfterToolCall, BeforeResponse, AfterComplete — for
  audit logging, billing, policy enforcement, and side effects
- **Dynamic MCP registration** — register Model Context Protocol servers (STDIO or SSE) at runtime
  via REST API without restart; per-server access policy control
- **Human-in-the-Loop approvals** — queue tool invocations for human review before execution
- **Tool policy engine** — channel-aware write-tool governance: deny write tools on specific channels, allow-list tools per channel
- **Prompt template versioning** — store, version, and promote prompt variants; Prompt Lab for
  automated evaluation with LLM judge scoring
- **RAG pipeline** — query transformation, PGVector retrieval, reranking, and context injection;
  dynamic ingestion governance via API
- **Multi-agent orchestration** — Sequential, Parallel, and Supervisor patterns with
  WorkerAgentTool wrapping
- **Multi-channel delivery** — REST + SSE streaming, Slack (Socket Mode / HTTP)
- **Admin audit log** — tamper-evident log of every admin action, accessible via API
- **Output guard rules** — runtime-configurable content policy applied to LLM responses
- **Resilience** — circuit breaker, configurable request/tool timeouts, fallback model chain
- **Observability** — OpenAPI/Swagger, Spring Actuator, Prometheus metrics, OpenTelemetry tracing
- **Security** — JWT authentication, security headers (HSTS, CSP), CORS control, MCP
  server allowlist
- **Kubernetes-ready** — production Helm chart with HPA, ingress, secret management, liveness and
  readiness probes, graceful shutdown

## Quick Start

### 1. Clone

```bash
git clone https://github.com/StarkFactory/arc-reactor.git
cd arc-reactor
```

### 2. Set your API key and run

**Fast path (bootstrap script):**

```bash
./scripts/dev/bootstrap-local.sh --api-key your-gemini-api-key --run
```

**Manual path:**

```bash
export GEMINI_API_KEY=your-gemini-api-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
./gradlew :arc-app:bootRun
```

Gemini is the default provider. To use OpenAI or Anthropic instead, set
`SPRING_AI_OPENAI_API_KEY` or `SPRING_AI_ANTHROPIC_API_KEY`.

### 3. Smoke test

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What is 3 + 5?"}'
```

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health check: `http://localhost:8080/actuator/health`

## Architecture

Arc Reactor separates concerns into three planes:

1. **Control Plane** — Admin APIs for policy, governance, and state management
2. **Execution Plane** — ReAct runtime (LLM + tool loop + safety pipelines)
3. **Channel Plane** — Delivery gateways (REST, Slack, Discord, LINE)

Request flow:

```
User / Channel Gateway
        |
        v
+----------------------------------+
|  Guard Pipeline (fail-close)     |
|  Rate limit → Input validation   |
|  → Unicode → Classification      |
+----------------------------------+
        |
        v
+----------------------------------+
|  Hook: BeforeStart               |
+----------------------------------+
        |
        v
+----------------------------------+
|  ReAct Executor                  |
|  LLM <-> Tool Loop               |
|  Retry / timeout / context trim  |
|  Structured output validation    |
+----------------------------------+
        |
        v
+----------------------------------+
|  Hook: AfterComplete             |
+----------------------------------+
        |
        v
Response + Audit Log + Metrics
```

## Modules

| Module | Description | When to use |
|---|---|---|
| `arc-app` | Executable assembly (`bootRun`, `bootJar`) | Always — entry point |
| `arc-app-lite` | Minimal executable assembly (`bootJar`) without slack/admin/error-report runtime modules | When minimizing attack surface and image size |
| `arc-core` | Agent engine: ReAct loop, Guard, Hook, memory, RAG, MCP, policy | Always — core runtime |
| `arc-web` | REST controllers, OpenAPI spec, security headers, CORS | Always — HTTP API |
| `arc-admin` | Admin module: metrics, tracing, ops dashboard | Optional — enable with `arc.reactor.admin.enabled=true` |
| `arc-slack` | Slack gateway (Socket Mode and HTTP Events) | When integrating with Slack |
| `arc-error-report` | Error-reporting extension (dedicated agent for error analysis) | Optional feature module |

## Configuration

Key properties with their defaults. Full reference: [`docs/en/getting-started/configuration-quickstart.md`](docs/en/getting-started/configuration-quickstart.md).

```yaml
arc:
  reactor:
    max-tool-calls: 10               # Max tool iterations per request
    max-tools-per-request: 20        # Max distinct tools exposed to LLM

    llm:
      default-provider: gemini
      temperature: 0.7
      max-output-tokens: 4096

    concurrency:
      max-concurrent-requests: 20
      request-timeout-ms: 30000      # 30 seconds
      tool-call-timeout-ms: 15000    # 15 seconds per tool

    guard:
      enabled: true
      rate-limit-per-minute: 20
      rate-limit-per-hour: 200

    boundaries:
      input-min-chars: 1
      input-max-chars: 10000
```

### Production bootstrap (recommended baseline)

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  flyway:
    enabled: true

arc:
  reactor:
    auth:
      enabled: true
      jwt-secret: ${JWT_SECRET}
    approval:
      enabled: true
    tool-policy:
      enabled: true
      dynamic:
        enabled: true
    output-guard:
      enabled: true
    rag:
      enabled: true
    mcp:
      security:
        allowed-server-names: [atlassian, filesystem]
```

### Feature toggles

| Feature | Default | Property |
|---|---|---|
| Guard | ON | `arc.reactor.guard.enabled` |
| Security headers | ON | `arc.reactor.security-headers.enabled` |
| Multimodal upload | ON | — |
| Auth (JWT) | REQUIRED | `arc.reactor.auth.enabled=true` |
| Approval (HITL) | OFF | `arc.reactor.approval.enabled` |
| Tool policy | OFF | `arc.reactor.tool-policy.dynamic.enabled` |
| RAG | OFF | `arc.reactor.rag.enabled` |
| Intent classification | OFF | `arc.reactor.intent.enabled` |
| Scheduler | OFF | `arc.reactor.scheduler.enabled` |
| Feedback | OFF | `arc.reactor.feedback.enabled` |
| CORS | OFF | `arc.reactor.cors.enabled` |
| Circuit breaker | OFF | `arc.reactor.circuit-breaker.enabled` |
| Output guard | OFF | `arc.reactor.output-guard.enabled` |
| Admin module | OFF | `arc.reactor.admin.enabled` |

## Deployment Options

### Local JVM

```bash
export GEMINI_API_KEY=your-api-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
./gradlew :arc-app:bootRun
```

### Lite bootJar (core + web only)

```bash
./gradlew :arc-app-lite:bootJar
java -jar arc-app-lite/build/libs/arc-app-lite-*.jar \
  --spring.ai.google.genai.api-key=$GEMINI_API_KEY \
  --arc.reactor.auth.jwt-secret=$ARC_REACTOR_AUTH_JWT_SECRET
```

### Docker Compose (app + PostgreSQL)

Includes pgvector for RAG workloads:

```bash
cp .env.example .env
# Edit .env — set GEMINI_API_KEY and optionally DB credentials

docker-compose up -d
```

To stop: `docker-compose down`

### Pre-built Docker image (ghcr.io)

Images are published automatically on every version tag:

```bash
docker pull ghcr.io/starkfactory/arc-reactor:4.5.0
docker run -e GEMINI_API_KEY=your-key -p 8080:8080 ghcr.io/starkfactory/arc-reactor:4.5.0
```

Available tags: exact version (e.g. `4.5.0`), minor stream (e.g. `4.5`), short SHA (`sha-<commit>`).

### Kubernetes (Helm)

A production-ready Helm chart is included at `helm/arc-reactor/`:

```bash
helm install arc-reactor ./helm/arc-reactor \
  -f helm/arc-reactor/values-production.yaml \
  --set secrets.geminiApiKey=your-api-key
```

The chart includes HPA, ingress, secret management, liveness/readiness probes, and graceful
shutdown. Requires Kubernetes 1.25+. See [`helm/arc-reactor/README.md`](helm/arc-reactor/README.md)
for the full reference.

## Control Plane API Reference

| Capability | API base | Activation |
|---|---|---|
| Chat runtime | `/api/chat` | Always |
| SSE streaming | `/api/chat/stream` | Always |
| Session and model ops | `/api/sessions`, `/api/models` | Always |
| Persona management | `/api/personas` | Always |
| Prompt template versioning | `/api/prompt-templates` | Always |
| MCP server registry | `/api/mcp/servers` | Always (ADMIN for read/write) |
| MCP access policy | `/api/mcp/servers/{name}/access-policy` | Always |
| Output guard rules | `/api/output-guard/rules` | `arc.reactor.output-guard.enabled=true` + `arc.reactor.output-guard.dynamic-rules-enabled=true` |
| Admin audit logs | `/api/admin/audits` | Always |
| Ops dashboard | `/api/ops` | Always |
| Authentication | `/api/auth` | `arc.reactor.auth.enabled=true` |
| Human-in-the-Loop approvals | `/api/approvals` | `arc.reactor.approval.enabled=true` |
| Dynamic tool policy | `/api/tool-policy` | `arc.reactor.tool-policy.dynamic.enabled=true` |
| Intent registry | `/api/intents` | `arc.reactor.intent.enabled=true` |
| RAG documents | `/api/documents` | `arc.reactor.rag.enabled=true` |
| RAG ingestion governance | `/api/rag-ingestion/policy`, `/api/rag-ingestion/candidates` | `arc.reactor.rag.ingestion.dynamic.enabled=true` |
| Scheduler (cron MCP) | `/api/scheduler/jobs` | `arc.reactor.scheduler.enabled=true` |
| Feedback | `/api/feedback` | `arc.reactor.feedback.enabled=true` |

### Example: Register an MCP server

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "filesystem",
    "description": "Local file tools",
    "transportType": "STDIO",
    "config": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
    },
    "autoConnect": true
  }'
```

### Example: Register Atlassian MCP for access-policy proxy

For Atlassian MCP access-policy proxying, include admin credentials in `config`:

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "atlassian",
    "transportType": "SSE",
    "config": {
      "url": "http://localhost:8085/sse",
      "adminToken": "change-me",
      "adminHmacRequired": true,
      "adminHmacSecret": "change-me-hmac"
    },
    "autoConnect": true
  }'
```

Then proxy policy updates via Arc Reactor:

```bash
curl -X PUT http://localhost:8080/api/mcp/servers/atlassian/access-policy \
  -H "Content-Type: application/json" \
  -d '{
    "allowedJiraProjectKeys": ["JAR","FSD"],
    "allowedConfluenceSpaceKeys": ["MFS","FRONTEND"],
    "allowedBitbucketRepositories": ["jarvis","arc-reactor"]
  }'
```

### Example: Apply a tool policy

```bash
curl -X PUT http://localhost:8080/api/tool-policy \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "writeToolNames": ["jira_create_issue", "bitbucket_merge_pr"],
    "denyWriteChannels": ["slack"],
    "allowWriteToolNamesInDenyChannels": ["jira_create_issue"]
  }'
```

### Example: Chat with a prompt template

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Summarize incident INC-1234",
    "promptTemplateId": "incident-template",
    "metadata": {"channel": "web"}
  }'
```

## Persistence

Arc Reactor uses PostgreSQL for all durable state. In-memory stores are available for development
but data is lost on restart.

| Domain | Store |
|---|---|
| Conversation memory | PostgreSQL (JDBC) or InMemory |
| Personas | PostgreSQL (JDBC) or InMemory |
| Prompt templates and versions | PostgreSQL (JDBC) |
| MCP server registry | PostgreSQL (JDBC) or InMemory |
| Output guard rules and audits | PostgreSQL (JDBC) |
| Admin audit logs | PostgreSQL (JDBC) |
| Approval requests (HITL) | PostgreSQL (JDBC) |
| Tool policy store | PostgreSQL (JDBC) |
| RAG ingestion policy | PostgreSQL (JDBC) |
| Feedback | PostgreSQL (JDBC) |
| Scheduler jobs | PostgreSQL (JDBC) |

Schema migrations are managed by Flyway. Enable with `SPRING_FLYWAY_ENABLED=true`.

## Documentation

- [Docs home](docs/en/README.md)
- [Getting started](docs/en/getting-started/README.md)
- [Configuration quickstart](docs/en/getting-started/configuration-quickstart.md)
- [Deployment guide](docs/en/getting-started/deployment.md)
- [Kubernetes reference](docs/en/getting-started/kubernetes-reference.md)
- [Architecture overview](docs/en/architecture/README.md)
- [ReAct loop internals](docs/en/architecture/react-loop.md)
- [MCP runtime management](docs/en/architecture/mcp/runtime-management.md)
- [Prompt Lab](docs/en/architecture/prompt-lab.md)
- [Authentication](docs/en/governance/authentication.md)
- [Human-in-the-loop](docs/en/governance/human-in-the-loop.md)
- [Tool policy admin](docs/en/governance/tool-policy-admin.md)
- [Security release framework](docs/en/governance/security-release-framework.md)
- [Support and compatibility policy](docs/en/governance/support-policy.md)
- [Tools reference](docs/en/reference/tools.md)
- [Metrics reference](docs/en/reference/metrics.md)
- [Slack integration](docs/en/integrations/slack/ops-runbook.md)
- [Agent benchmarking](docs/en/engineering/agent-benchmarking.md)
- [Release notes](docs/en/releases/README.md)

## Security Notes

- JWT auth is mandatory. Keep `arc.reactor.auth.enabled=true` in all environments.
- Provide `ARC_REACTOR_AUTH_JWT_SECRET` via environment variable (minimum 32 bytes); do not commit secrets.
- Restrict MCP server exposure with `arc.reactor.mcp.security.allowed-server-names`.
- Use tool policy and approval gates for high-risk write operations.
- Enable Flyway with PostgreSQL so governance data is durable across restarts.
- Security policy: [`SECURITY.md`](SECURITY.md)

## Known Constraints

- MCP SDK `0.17.2` does not support streamable HTTP transport. Use SSE or STDIO.
- PostgreSQL is required for persistent stores (`spring.datasource.url=jdbc:postgresql://...`).
- External integration tests are opt-in: `./gradlew test -PincludeIntegration -PincludeExternalIntegration`.

## Build and Test Commands

```bash
./gradlew test                                       # Full test suite
./gradlew test --tests "com.arc.reactor.agent.*"    # Package filter
./gradlew compileKotlin compileTestKotlin           # Compile check (target: 0 warnings)
./gradlew :arc-core:test -Pdb=true                 # Include PostgreSQL/PGVector/Flyway deps
./gradlew test -PincludeIntegration                # Include @Tag("integration") tests
./gradlew :arc-app:bootRun                         # Run locally
```

## Contributing

1. Fork the repository and create a feature branch: `git checkout -b feature/my-feature`
2. Make changes, write or update tests, and ensure `./gradlew test` passes
3. Follow [Conventional Commits](https://www.conventionalcommits.org/) for commit messages
4. Open a pull request — CI must be green before merge

Full guide: [`CONTRIBUTING.md`](CONTRIBUTING.md) | [`SUPPORT.md`](SUPPORT.md) | [`CHANGELOG.md`](CHANGELOG.md)

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
