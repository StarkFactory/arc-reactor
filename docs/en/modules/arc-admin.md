# arc-admin

## Overview

arc-admin is the operational control plane for Arc Reactor. It solves the problem of running AI agents in a multi-tenant production environment without visibility into cost, reliability, or quota consumption.

The module provides:

- **Metric collection** — every agent execution, tool call, token usage, guard decision, and session is captured into a lock-free ring buffer and flushed to TimescaleDB in background batches
- **Cost tracking** — model pricing is stored and looked up per provider/model/time, so every token event carries a USD cost estimate
- **Tenant management** — tenants have plans (FREE/STARTER/BUSINESS/ENTERPRISE), quotas, SLO targets, and lifecycle states (ACTIVE/SUSPENDED/DEACTIVATED)
- **Quota enforcement** — a `BeforeAgentStartHook` with three-layer defense (local counter → Caffeine cache → DB with circuit breaker) rejects requests when monthly limits are exceeded
- **Alerting** — rule-based alerts fire on static thresholds, anomaly detection (σ-based), or error-budget burn rates; a scheduler evaluates rules and dispatches notifications
- **SLO tracking** — availability and p99 latency targets per tenant with error-budget burn-rate computation
- **Distributed tracing** — OTel spans for every agent execution and tool call, exportable via OTLP or TimescaleDB
- **Admin REST API** — platform-wide and tenant-scoped dashboards, quota views, CSV exports, pricing management, and alert CRUD

## Activation

**Property:**
```yaml
arc:
  reactor:
    admin:
      enabled: true
```

**Gradle dependency:**
```kotlin
implementation("com.arc.reactor:arc-admin")
```

A `DataSource` (PostgreSQL with TimescaleDB extension) is required for the JDBC tier — metrics storage, tenant store, pricing store, alert store, query service, and dashboard service all activate automatically when a `DataSource` bean is present. Without a `DataSource`, only the in-memory stores and ring-buffer collection pipeline activate.

## Key Components

| Class | Role |
|---|---|
| `AdminAutoConfiguration` | Base beans: tenant store, pricing store, cost calculator, metric ring buffer, hooks |
| `AdminJdbcConfiguration` | JDBC tier: all stores, metric writer, query/SLO/alert/dashboard services, quota hook |
| `TracingAutoConfiguration` | OTel SDK setup, Micrometer tracing bridge, OTLP/TimescaleDB exporter wiring |
| `MetricRingBuffer` | Lock-free Disruptor-inspired ring buffer for metric events (single-consumer drain) |
| `MetricCollectionHook` | `AfterAgentCompleteHook` + `AfterToolCallHook` (order=200) that publishes all metric events |
| `MetricWriter` | Background thread that drains the ring buffer and writes batches to `MetricEventStore` |
| `MetricCollectorAgentMetrics` | `@Primary AgentMetrics` implementation; captures LLM token/cost data via the metrics interface |
| `QuotaEnforcerHook` | `BeforeAgentStartHook` (order=5); three-layer quota check with fail-open semantics |
| `AlertEvaluator` | Evaluates alert rules: STATIC_THRESHOLD, BASELINE_ANOMALY, ERROR_BUDGET_BURN_RATE |
| `AlertScheduler` | Runs `AlertEvaluator.evaluateAll()` on a fixed schedule and dispatches notifications |
| `AgentTracingHooks` | Four-type hook (order=199) creating `gen_ai.agent.execute` and `gen_ai.tool.execute` OTel spans |
| `CostCalculator` | Looks up model pricing and computes USD cost from token counts (cached 5 minutes) |
| `TenantService` | Tenant lifecycle: create, suspend, activate |
| `DashboardService` | Aggregated dashboard queries: overview, usage, quality, tools, cost |
| `SloService` | Availability and latency SLO status plus error-budget burn-rate |
| `PlatformAdminController` | `GET/POST /api/admin/platform/*` — tenant CRUD, pricing, alerts, platform health |
| `TenantAdminController` | `GET /api/admin/tenant/*` — tenant dashboards, SLO, quota, CSV export |
| `McpMetricReporter` | Lightweight HTTP reporter for MCP servers to push their own tool-call metrics |

## Configuration

All properties are under the prefix `arc.reactor.admin`.

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Activates the module |
| `timescale-enabled` | `true` | Reserved; indicates TimescaleDB is the backing store |
| `tracing.enabled` | `true` | Enable agent/tool span creation |
| `tracing.timescale-export` | `true` | Export spans to TimescaleDB via `TimescaleSpanExporter` |
| `tracing.otlp.enabled` | `false` | Enable OTLP span export |
| `tracing.otlp.endpoint` | `""` | OTLP endpoint URL |
| `tracing.otlp.protocol` | `"http/protobuf"` | OTLP protocol |
| `tracing.otlp.headers` | `{}` | Additional OTLP headers (e.g., auth) |
| `collection.ring-buffer-size` | `8192` | Ring buffer capacity (rounded up to next power of two) |
| `collection.flush-interval-ms` | `1000` | How often `MetricWriter` drains the buffer |
| `collection.batch-size` | `1000` | Max events per DB write batch |
| `collection.writer-threads` | `1` | Writer thread count (must stay at 1 — drain is single-consumer) |
| `retention.raw-days` | `90` | Raw metric retention in TimescaleDB |
| `retention.hourly-days` | `365` | Hourly aggregate retention |
| `retention.daily-days` | `1825` | Daily aggregate retention (5 years) |
| `retention.audit-years` | `7` | Audit log retention |
| `retention.compression-after-days` | `7` | Compress TimescaleDB chunks after N days |
| `slo.default-availability` | `0.995` | Default SLO availability target (99.5%) |
| `slo.default-latency-p99-ms` | `10000` | Default SLO p99 latency target |
| `scaling.instance-id` | hostname | Instance identifier injected into OTel spans |
| `scaling.mode` | `DIRECT_WRITE` | `DIRECT_WRITE` or `KAFKA` (Kafka mode is reserved) |

## Integration

arc-admin integrates with the core framework through the standard hook and metrics interfaces:

**Hooks registered automatically:**

- `MetricCollectionHook` (order=200) — implements `AfterAgentCompleteHook` and `AfterToolCallHook`; publishes `AgentExecutionEvent`, `ToolCallEvent`, `GuardEvent`, `SessionEvent`, `McpHealthEvent` to the ring buffer
- `HitlEventHook` — publishes `HitlEvent` when a tool call is held for human-in-the-loop approval
- `QuotaEnforcerHook` (order=5) — implements `BeforeAgentStartHook`; activates only when both `DataSource` and `CircuitBreakerRegistry` beans are present
- `AgentTracingHooks` (order=199) — activates only when a `Tracer` bean is present

**Metrics interface:**

`MetricCollectorAgentMetrics` is registered as `@Primary AgentMetrics`. The core executor calls `AgentMetrics` after every LLM step to record token counts and cost. Token data flows from the executor into `TokenUsageEvent` objects in the ring buffer.

**Tenant resolution:**

Incoming HTTP requests pass through `TenantWebFilter`, which calls `TenantResolver` to extract a `tenantId` from request headers (e.g., `X-Tenant-ID`). The resolved ID is stored in the WebFlux exchange attribute and picked up by `MetricCollectionHook` via `context.metadata["tenantId"]`.

**Admin access control:**

All admin endpoints use `AdminAuthHelper.isAdmin(exchange)` and `forbiddenResponse()` from the shared `AdminAuthHelper` file. When `arc.reactor.auth.enabled=false`, all requests are treated as admin.

## Code Examples

**Minimal production configuration:**

```yaml
arc:
  reactor:
    admin:
      enabled: true
      collection:
        ring-buffer-size: 16384
        flush-interval-ms: 500
      retention:
        raw-days: 30
      tracing:
        enabled: true
        otlp:
          enabled: true
          endpoint: "http://otel-collector:4318"

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/arc_reactor
```

**Creating a tenant via REST:**

```bash
curl -X POST http://localhost:8080/api/admin/platform/tenants \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Corp", "slug": "acme", "plan": "BUSINESS"}'
```

**Creating an alert rule (static threshold on error rate):**

```bash
curl -X POST http://localhost:8080/api/admin/platform/alerts/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "High error rate",
    "type": "STATIC_THRESHOLD",
    "metric": "error_rate",
    "threshold": 0.05,
    "windowMinutes": 15,
    "severity": "HIGH",
    "tenantId": "acme-id"
  }'
```

**MCP server reporting metrics back to arc-admin:**

```kotlin
val reporter = McpMetricReporter(
    endpoint = "http://arc-reactor:8080/api/admin/metrics/ingest",
    tenantId = "tenant-abc",
    serverName = "my-mcp-server"
)
reporter.start()

// In a tool handler:
reporter.reportToolCall("my_tool", durationMs = 120, success = true, runId = runId)

// On application shutdown:
reporter.stop()
```

**Exporting tenant execution data as CSV:**

```bash
curl "http://localhost:8080/api/admin/tenant/export/executions?fromMs=1700000000000" \
  -o executions.csv
```

## Common Pitfalls / Notes

**Ring buffer is single-consumer only.** `MetricRingBuffer.drain()` is not concurrent-safe. Keep `collection.writer-threads=1` (the default). Increasing it will cause duplicate reads and data loss.

**QuotaEnforcerHook is fail-open.** If the circuit breaker is open or the DB is unreachable, the request is allowed through with a warning log. This is intentional to prevent DB unavailability from blocking all agent traffic.

**TimescaleDB is required for full functionality.** Without a `DataSource`, no metric storage, dashboards, SLO, or alert evaluation is available. The ring buffer and hooks still register but events are dropped at the writer boundary.

**Admin controllers require a DataSource.** `PlatformAdminController` and `TenantAdminController` are both annotated `@ConditionalOnBean(DataSource::class)`. They do not activate in in-memory-only mode.

**OTLP and TimescaleDB tracing are independent.** Both can be active at the same time. OTLP is off by default; enable it by setting `tracing.otlp.enabled=true` and providing an endpoint.

**Tenant ID "default" is always allowed.** `QuotaEnforcerHook` skips enforcement when `tenantId == "default"`. Ensure your `TenantResolver` returns a real tenant ID for production traffic.

**HITL wait detection in tracing.** `AgentTracingHooks` infers HITL wait time by comparing total elapsed time against tool execution duration. If the difference exceeds 100 ms, it tags the span with `gen_ai.tool.hitl.required=true`.
