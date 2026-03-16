# Tracing

## Overview

Arc Reactor provides a lightweight tracing abstraction (`ArcReactorTracer`) that instruments agent execution without requiring a hard dependency on OpenTelemetry. When OTel is present, spans are emitted to your configured exporter (Jaeger, Zipkin, OTLP, etc.). When OTel is absent, a no-op tracer is used with zero overhead.

```
arc.agent.request                          ← top-level span (entire execution)
  ├── arc.agent.guard                      ← guard pipeline check
  ├── arc.agent.llm.call [0]              ← first LLM invocation
  ├── arc.agent.tool.call [calc, 0]       ← parallel tool calls
  ├── arc.agent.tool.call [search, 1]
  ├── arc.agent.llm.call [1]              ← second LLM invocation (after tools)
  └── ...
```

**Enabled by default** — safe because the no-op tracer has zero cost when OpenTelemetry is not on the classpath.

---

## Architecture

### ArcReactorTracer Interface

The core abstraction is a single interface with one method:

```kotlin
interface ArcReactorTracer {

    fun startSpan(name: String, attributes: Map<String, String> = emptyMap()): SpanHandle

    interface SpanHandle : AutoCloseable {
        fun setError(e: Throwable)
        fun setAttribute(key: String, value: String)
        override fun close()   // Ends the span. Idempotent.
    }
}
```

Key design decisions:

- **No OTel types in the API** — the interface uses only `String` and `Map`, so it compiles without OTel on the classpath
- **`SpanHandle` is `AutoCloseable`** — supports Kotlin `use {}` blocks for guaranteed span closure
- **Thread-safe** — implementations must be safe to call from multiple coroutines
- **Idempotent close** — calling `close()` multiple times is safe (no-op after the first)

### NoOpArcReactorTracer

Used when OpenTelemetry is unavailable or tracing is disabled:

```kotlin
class NoOpArcReactorTracer : ArcReactorTracer {
    override fun startSpan(name: String, attributes: Map<String, String>): SpanHandle =
        NoOpSpanHandle   // singleton object — zero allocation

    private object NoOpSpanHandle : ArcReactorTracer.SpanHandle {
        override fun setError(e: Throwable) = Unit
        override fun setAttribute(key: String, value: String) = Unit
        override fun close() = Unit
    }
}
```

- Returns the same singleton `NoOpSpanHandle` for every call — zero allocation per span
- All methods are empty — zero overhead

### OtelArcReactorTracer

The OpenTelemetry-backed implementation:

```kotlin
class OtelArcReactorTracer(private val tracer: Tracer) : ArcReactorTracer {

    override fun startSpan(name: String, attributes: Map<String, String>): SpanHandle {
        val builder = tracer.spanBuilder(name).setParent(Context.current())
        for ((key, value) in attributes) {
            builder.setAttribute(key, value)
        }
        val span = builder.startSpan()
        return OtelSpanHandle(span)
    }
}
```

Key behavior:

- Each span is a **child of the current OTel context** (`Context.current()`)
- Attributes are attached at span creation time
- `setError()` sets `StatusCode.ERROR` and records the exception via `recordException()`
- `close()` calls `span.end()` to finalize the span

---

## Configuration

```yaml
arc:
  reactor:
    tracing:
      enabled: true                # Enable span emission (default: true)
      service-name: arc-reactor    # service.name attached to spans (default: "arc-reactor")
      include-user-id: false       # Include user.id attribute in spans (default: false — PII safety)
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master switch. When `false`, `NoOpArcReactorTracer` is used regardless of OTel presence. |
| `service-name` | `"arc-reactor"` | The `service.name` used when obtaining the OTel `Tracer` instance. |
| `include-user-id` | `false` | Whether to include `user.id` as a span attribute. Disabled by default to prevent PII leakage in traces. |

---

## Auto-Configuration

Bean resolution order (first match wins via `@ConditionalOnMissingBean`):

1. **`arcReactorOtelTracer`** — created when all of these conditions are true:
   - `arc.reactor.tracing.enabled=true` (default)
   - `io.opentelemetry.api.OpenTelemetry` class is on the classpath
   - An `OpenTelemetry` bean exists in the Spring context
   - No user-provided `ArcReactorTracer` bean already exists

2. **`noOpTracer`** — fallback when the OTel tracer was not registered

Users can override either bean by providing their own `ArcReactorTracer` `@Bean`. The `@ConditionalOnMissingBean` annotation ensures the custom bean takes precedence.

---

## Span Reference

The framework emits four span types during agent execution:

### `arc.agent.request`

Top-level span covering the entire agent execution.

| Attribute | Description |
|-----------|-------------|
| `session.id` | Session ID from command metadata (empty string if absent) |
| `agent.mode` | Agent execution mode (e.g., `chat`, `streaming`) |
| `user.id` | User ID (only when `include-user-id=true`; uses `"anonymous"` for null) |
| `error.code` | Error code on failure (e.g., `RATE_LIMITED`, `TIMEOUT`) |
| `error.message` | Error message on failure (truncated to 500 chars) |

### `arc.agent.guard`

Covers the guard pipeline check (input validation, rate limiting, injection detection).

| Attribute | Description |
|-----------|-------------|
| `guard.result` | `"passed"` or `"rejected"` |

### `arc.agent.llm.call`

One span per LLM invocation within the ReAct loop.

| Attribute | Description |
|-----------|-------------|
| `llm.call.index` | Zero-based index of the LLM call within this execution |

### `arc.agent.tool.call`

One span per tool invocation (parallel tool calls produce parallel spans).

| Attribute | Description |
|-----------|-------------|
| `tool.name` | Name of the invoked tool |
| `tool.call.index` | Global index of this tool call across the entire execution |

---

## Enabling OpenTelemetry with Spring Boot

### Step 1: Add dependencies

Add the OpenTelemetry Spring Boot starter to your build:

```kotlin
// build.gradle.kts
implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.12.0")
```

This brings in the OTel API, SDK, and auto-configuration for Spring Boot.

### Step 2: Configure the exporter

```yaml
# application.yml
otel:
  exporter:
    otlp:
      endpoint: http://localhost:4317   # OTLP gRPC endpoint (Jaeger, Tempo, etc.)
  resource:
    attributes:
      service.name: my-agent-service

arc:
  reactor:
    tracing:
      enabled: true
      service-name: my-agent-service
```

### Step 3: Verify

Start the application and trigger an agent request. You should see spans in your tracing backend:

```
my-agent-service: arc.agent.request (2340ms)
  ├── arc.agent.guard (12ms)
  ├── arc.agent.llm.call (1800ms)
  ├── arc.agent.tool.call [calculator] (200ms)
  └── arc.agent.llm.call (320ms)
```

---

## Integration with Jaeger

```yaml
otel:
  exporter:
    otlp:
      endpoint: http://jaeger:4317
```

Access the Jaeger UI at `http://localhost:16686`. Filter by service name `arc-reactor` (or your custom `service-name`).

## Integration with Zipkin

```yaml
otel:
  exporter:
    zipkin:
      endpoint: http://zipkin:9411/api/v2/spans
```

Access the Zipkin UI at `http://localhost:9411`.

## Integration with Spring Boot Actuator

When `spring-boot-starter-actuator` and OTel are both present, traces are automatically exported. No additional Arc Reactor configuration is required beyond ensuring `arc.reactor.tracing.enabled=true` (the default).

```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # Sample all requests (reduce in production)
```

---

## Custom Tracer Implementation

Override the default by providing your own bean:

```kotlin
@Bean
fun arcReactorTracer(): ArcReactorTracer {
    return object : ArcReactorTracer {
        override fun startSpan(
            name: String,
            attributes: Map<String, String>
        ): ArcReactorTracer.SpanHandle {
            logger.info { "Span started: $name, attrs=$attributes" }
            return object : ArcReactorTracer.SpanHandle {
                override fun setError(e: Throwable) {
                    logger.error(e) { "Span error: $name" }
                }
                override fun setAttribute(key: String, value: String) {
                    logger.debug { "Span attr: $key=$value" }
                }
                override fun close() {
                    logger.info { "Span ended: $name" }
                }
            }
        }
    }
}
```

Because `@ConditionalOnMissingBean` is used, your custom bean takes precedence.

---

## Common Pitfalls

| Pitfall | Explanation |
|---------|-------------|
| **OTel API on classpath but no bean** | If the OTel API jar is present but no `OpenTelemetry` bean is registered (e.g., missing auto-configuration), the `NoOpArcReactorTracer` is used silently. Check logs for `"ArcReactorTracer: using NoOp"`. |
| **PII in traces** | `include-user-id` is `false` by default. Enabling it sends user IDs to your tracing backend. Ensure your trace storage complies with privacy requirements. |
| **Span not closing** | Always use `use {}` blocks or `try/finally` to close spans. Unclosed spans leak memory and produce incomplete traces. The framework already does this internally. |
| **CancellationException in spans** | The executor correctly handles `CancellationException` — it sets the error on the span and rethrows. Custom tracer implementations should not swallow `CancellationException`. |
| **High-cardinality attributes** | Avoid adding high-cardinality values (full prompts, response bodies) as span attributes. Use `error.message` truncation (500 chars) as a guide. |
| **Sampling in production** | With full sampling (`1.0`), every request produces traces. For high-traffic deployments, reduce the sampling probability to avoid excessive storage costs. |
