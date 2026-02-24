# Arc Reactor

[![CI](https://github.com/StarkFactory/arc-reactor/actions/workflows/ci.yml/badge.svg)](https://github.com/StarkFactory/arc-reactor/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-purple.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-orange.svg)](https://spring.io/projects/spring-ai)

[한국어](README.ko.md)

**Enterprise-oriented, centrally managed AI Agent runtime built on Spring AI.**

Arc Reactor is an open-source project template that you fork and operate as your own AI agent platform.
It focuses on governance and runtime control: Guard pipeline, Hook lifecycle, dynamic MCP registration,
Human-in-the-Loop approvals, tool policy, prompt/version management, audit logging, and multi-channel
integration (Web, Slack, Discord, LINE).

> Arc Reactor is **not** a drop-in framework dependency. The intended model is: **fork -> customize -> deploy**.

## Fork Responsibility Boundary

- Arc Reactor is provided **AS IS** under Apache-2.0 (`LICENSE`).
- Upstream maintainers are responsible for this upstream repository only.
- Downstream fork operators are solely responsible for their own deployment and operations:
  security hardening, secrets, access control, compliance, incident response, and production change management.
- Upstream maintainers do not assume liability for outages, breaches, data loss, or compliance failures in
  downstream forks or customized deployments.
- No SLA, warranty, or indemnification is provided for downstream operations.

## Why Arc Reactor for Enterprise

- Central control plane APIs for prompts, personas, MCP servers, tool policy, output guard rules, approvals, and audits
- Execution safety by default: guard fail-close, bounded ReAct loops, timeout controls, and optional circuit breaker
- Channel-aware governance (for example deny write tools on chat channels, require approval on web)
- Multi-agent orchestration patterns (Sequential, Parallel, Supervisor)
- Production diagnostics: Swagger/OpenAPI, actuator endpoints, metrics hooks, and operational controllers

## Platform Model

Arc Reactor separates concerns into three layers:

1. **Control Plane**: Admin APIs for policy/governance/state changes
2. **Execution Plane**: ReAct runtime (LLM + tool loop + safety pipelines)
3. **Channel Plane**: Delivery gateways (REST, Slack, Discord, LINE)

```text
User / Channel Gateway
        |
        v
+------------------------------+
| Guard Pipeline (fail-close)  |
+------------------------------+
        |
        v
+------------------------------+
| Hook BeforeStart             |
+------------------------------+
        |
        v
+------------------------------+
| ReAct Executor               |
| LLM <-> Tool Loop            |
| + retry / timeout / trimming |
+------------------------------+
        |
        v
+------------------------------+
| Hook AfterComplete           |
+------------------------------+
        |
        v
Response + Audit + Metrics
```

## Quick Start (Local)

### 1. Clone

```bash
git clone https://github.com/StarkFactory/arc-reactor.git
cd arc-reactor
```

### 2. Set one provider key (Gemini default)

```bash
export GEMINI_API_KEY=your-api-key
```

### 3. Run

```bash
./gradlew :arc-app:bootRun
```

### 4. Smoke test

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"What is 3 + 5?"}'
```

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

## Production Bootstrap (Recommended Baseline)

Start from this minimal enterprise profile and tighten from there:

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
      ingestion:
        enabled: true
        dynamic:
          enabled: true
    mcp:
      security:
        allowed-server-names: [atlassian, filesystem]
```

Recommended environment variables:

- `GEMINI_API_KEY` (or another provider key)
- `JWT_SECRET`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_FLYWAY_ENABLED=true`

## API Version Contract

- Request header (optional): `X-Arc-Api-Version` (default: `v1`)
- Unsupported version -> `400 Bad Request` with standard `ErrorResponse`
- Response headers:
  - `X-Arc-Api-Version` (current version)
  - `X-Arc-Api-Supported-Versions` (comma-separated supported set)
- Config:
  - `arc.reactor.api-version.enabled=true` (default)
  - `arc.reactor.api-version.current=v1` (default)
  - `arc.reactor.api-version.supported=v1` (default)

## Enterprise Control Plane APIs

| Capability | API Base | Activation |
|---|---|---|
| Chat runtime | `/api/chat` | Always |
| API version contract | all `/api/**` | Always (`arc.reactor.api-version.enabled=true` by default) |
| Session/model ops | `/api/sessions`, `/api/models` | Always |
| Persona management | `/api/personas` | Always |
| Prompt template versioning | `/api/prompt-templates` | Always |
| MCP registry and lifecycle | `/api/mcp/servers` | Always |
| MCP access policy proxy | `/api/mcp/servers/{name}/access-policy` | Always |
| Output guard rule admin | `/api/output-guard/rules` | Always |
| Admin audit logs | `/api/admin/audits` | Always |
| Ops dashboard | `/api/ops` | Always |
| Authentication | `/api/auth` | `arc.reactor.auth.enabled=true` |
| Human-in-the-Loop approval queue | `/api/approvals` | `arc.reactor.approval.enabled=true` |
| Dynamic tool policy | `/api/tool-policy` | `arc.reactor.tool-policy.dynamic.enabled=true` |
| Intent registry | `/api/intents` | `arc.reactor.intent.enabled=true` |
| RAG document APIs | `/api/documents` | `arc.reactor.rag.enabled=true` |
| RAG ingestion governance | `/api/rag-ingestion/policy`, `/api/rag-ingestion/candidates` | `arc.reactor.rag.ingestion.dynamic.enabled=true` |
| Scheduler (cron MCP execution) | `/api/scheduler/jobs` | `arc.reactor.scheduler.enabled=true` |
| Feedback APIs | `/api/feedback` | `arc.reactor.feedback.enabled=true` |

## Control Plane API Walkthrough

### 1. Register an MCP server

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

### 2. Apply dynamic tool policy (requires `tool-policy.dynamic.enabled=true`)

```bash
curl -X PUT http://localhost:8080/api/tool-policy \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "writeToolNames": ["jira_create_issue", "bitbucket_merge_pr"],
    "denyWriteChannels": ["slack"],
    "allowWriteToolNamesInDenyChannels": ["jira_create_issue"],
    "allowWriteToolNamesByChannel": {
      "discord": ["jira_create_issue"]
    },
    "denyWriteMessage": "Error: Write tools are disabled in this channel"
  }'
```

### 3. Submit chat with a managed prompt template

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Summarize incident INC-1234",
    "promptTemplateId": "incident-template",
    "metadata": {"channel": "web", "sessionId": "ops-session-1"}
  }'
```

### 4. Review pending approvals (when HITL is enabled)

```bash
curl http://localhost:8080/api/approvals
```

If your deployment enables auth, include `Authorization: Bearer <token>` in all admin calls.

## Runtime Capabilities

| Area | What You Get |
|---|---|
| Agent loop | ReAct execution with bounded tool-call iterations |
| Safety | 5-stage guard pipeline + optional output guard policy |
| Extensibility | 4 hook lifecycle points and custom tool callbacks |
| Tooling | Local tools + MCP tools (STDIO/SSE) with runtime registration |
| Memory | Session-based conversation history (InMemory/JDBC) |
| RAG | Query transform, retrieval, rerank, context injection |
| Output modes | Text/JSON/YAML structured output with validation/repair |
| Concurrency | Request timeout, tool timeout, concurrent tool execution |
| Resilience | Retry, circuit breaker, fallback model chain (opt-in) |
| Multi-agent | Sequential / Parallel / Supervisor orchestrators |
| Governance | Tool policy, approval flow, admin audit, prompt/version management |

## Feature Toggles (Safe Defaults)

| Feature | Default |
|---|---|
| Guard | ON |
| Security headers | ON |
| Multimodal upload | ON |
| Auth (JWT) | OFF |
| Approval (HITL) | OFF |
| Tool policy dynamic control | OFF |
| RAG | OFF |
| RAG ingestion dynamic control | OFF |
| Intent classification | OFF |
| Scheduler | OFF |
| Feedback | OFF |
| CORS | OFF |
| Circuit breaker | OFF |
| Fallback strategy | OFF |
| Output guard | OFF |

## Security Notes for Production

- Enable auth in production. If auth is disabled, admin checks treat requests as admin for convenience.
- Provide a strong `JWT_SECRET` via environment variable; do not commit secrets.
- Restrict MCP server exposure with `arc.reactor.mcp.security.allowed-server-names`.
- Keep write tools under policy control (`tool-policy`) and approval gates (`approval`) for high-risk actions.
- Keep `spring.flyway.enabled=true` with PostgreSQL so governance data is durable across restarts.

## Build, Test, and Run Commands

```bash
./gradlew test
./gradlew :arc-core:test
./gradlew :arc-web:test
./gradlew :arc-slack:test
./gradlew :arc-discord:test
./gradlew :arc-line:test
./gradlew compileKotlin compileTestKotlin
./gradlew :arc-app:bootRun
```

With PostgreSQL/JDBC dependencies included:

```bash
./gradlew :arc-core:test -Pdb=true
```

## Module Layout

| Module | Role |
|---|---|
| `arc-app/` | Executable assembly module (`bootRun`, `bootJar`) |
| `arc-core/` | Agent engine (ReAct, guard, hook, memory, RAG, MCP, policy) |
| `arc-web/` | REST controllers, OpenAPI, security headers/CORS |
| `arc-slack/` | Slack gateway |
| `arc-discord/` | Discord gateway |
| `arc-line/` | LINE gateway |
| `arc-error-report/` | Error-reporting extension module |

## Persistence Strategy

Arc Reactor requires PostgreSQL in runtime and uses JDBC-backed stores.
Startup fails fast when `spring.datasource.url` is missing or not `jdbc:postgresql:...`.

| Domain State | Runtime backing |
|---|---|
| Conversation memory | PostgreSQL (JDBC) |
| Personas | PostgreSQL (JDBC) |
| Prompt templates/versions | PostgreSQL (JDBC) |
| MCP server registry | PostgreSQL (JDBC) |
| Output guard rules + audits | PostgreSQL (JDBC) |
| Admin audit logs | PostgreSQL (JDBC) |
| Approval requests (HITL) | PostgreSQL (JDBC, when `arc.reactor.approval.enabled=true`) |
| Feedback | PostgreSQL (JDBC, when `arc.reactor.feedback.enabled=true`) |
| Scheduler jobs | PostgreSQL (JDBC, when `arc.reactor.scheduler.enabled=true`) |
| Tool policy store | PostgreSQL (JDBC) |
| RAG ingestion policy store | PostgreSQL (JDBC) |

## Quality Signals

Verified locally on **February 21, 2026**:

- `./gradlew test`: passed
- `./gradlew compileKotlin compileTestKotlin`: passed
- Test report aggregate: **1425 tests**, **0 failures**, **0 errors**, **1 skipped**

Repository-level quality guards include:

- CI workflow (`.github/workflows/ci.yml`) with docs-link guard and duration guard
- Security baseline workflow (`.github/workflows/security-baseline.yml`) for secret and vulnerability scans
- Nightly matrix workflow (`.github/workflows/nightly-matrix.yml`)
- Release workflow (`.github/workflows/release.yml`)
- Slack runtime validation workflow (`.github/workflows/slack-runtime-validation.yml`)

Maturity snapshot (repository state on February 21, 2026):

- 7 Gradle modules (`arc-app`, `arc-core`, `arc-web`, `arc-slack`, `arc-discord`, `arc-line`, `arc-error-report`)
- 22 REST controllers in `arc-web`
- 61 English documentation files under `docs/en`
- 435 Kotlin source files and 190 Kotlin test files

## What to Customize After Forking

- Implement business tools in `arc-core/src/main/kotlin/com/arc/reactor/tool/`
- Replace/extend guard stages for domain-specific policy decisions
- Add lifecycle hooks for audit, billing, policy enforcement, and side-effect controls
- Configure auth and admin role model for your environment
- Register MCP servers through API and apply per-server access policy
- Provision PostgreSQL and configure `spring.datasource.*` + Flyway

## Deployment Options

### Local JVM

```bash
export GEMINI_API_KEY=your-api-key
./gradlew :arc-app:bootRun
```

### Docker Compose (app + PostgreSQL)

```bash
cp .env.example .env
# edit .env

docker-compose up -d
docker-compose down
```

## Documentation Map

- Docs home: `docs/en/README.md`
- Architecture: `docs/en/architecture/architecture.md`
- ReAct internals: `docs/en/architecture/react-loop.md`
- MCP runtime: `docs/en/architecture/mcp/runtime-management.md`
- Tool reference: `docs/en/reference/tools.md`
- Configuration quickstart: `docs/en/getting-started/configuration-quickstart.md`
- Deployment: `docs/en/getting-started/deployment.md`
- Kubernetes reference: `docs/en/getting-started/kubernetes-reference.md`
- Authentication: `docs/en/governance/authentication.md`
- Human-in-the-loop: `docs/en/governance/human-in-the-loop.md`
- Tool policy admin: `docs/en/governance/tool-policy-admin.md`
- Security release framework: `docs/en/governance/security-release-framework.md`
- Support and compatibility policy: `docs/en/governance/support-policy.md`
- Metrics: `docs/en/reference/metrics.md`
- Agent benchmarking: `docs/en/engineering/agent-benchmarking.md`

## Known Constraints

- MCP SDK `0.17.2` does not support streamable HTTP transport. Use **SSE** or **STDIO**.
- PostgreSQL is required at runtime (`spring.datasource.url=jdbc:postgresql://...`).
- External integration tests are opt-in (`-PincludeIntegration`, `-PincludeExternalIntegration`).

## Open-Source Release Readiness Checklist

Before broad public adoption, strongly consider adding:

- Public benchmark baseline (latency/throughput by scenario)
- Compatibility and support policy (LTS window, deprecation policy)
- Security scan summary publication policy in release notes
- Kubernetes reference manifests (or Helm chart) for standard enterprise deployment

## Contributing

- Contribution guide: `CONTRIBUTING.md`
- Support policy: `SUPPORT.md`
- Security policy: `SECURITY.md`
- Changelog: `CHANGELOG.md`

## License

Apache License 2.0. See `LICENSE`.
