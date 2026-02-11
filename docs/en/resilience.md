# Circuit Breaker

## Overview

The circuit breaker prevents cascading failures by tracking consecutive errors and short-circuiting calls when the failure rate exceeds a threshold. This protects both the LLM provider and the application from repeated failing requests.

Arc Reactor uses a **Kotlin-native** circuit breaker (no Resilience4j or external dependencies).

**Disabled by default** — opt-in via configuration.

---

## State Machine

```
CLOSED ──(failures >= threshold)──> OPEN
OPEN ──(resetTimeout elapsed)──> HALF_OPEN
HALF_OPEN ──(success)──> CLOSED
HALF_OPEN ──(failure)──> OPEN
```

| State | Behavior |
|-------|----------|
| **CLOSED** | Normal operation. Consecutive failures are counted. Resets on success. |
| **OPEN** | All calls rejected immediately with `CircuitBreakerOpenException`. |
| **HALF_OPEN** | A limited number of trial calls are allowed. Success → CLOSED, failure → OPEN. |

---

## Configuration

```yaml
arc:
  reactor:
    circuit-breaker:
      enabled: true              # Enable circuit breaker (default: false)
      failure-threshold: 5       # Consecutive failures before opening (default: 5)
      reset-timeout-ms: 30000    # Time before OPEN → HALF_OPEN (default: 30000)
      half-open-max-calls: 1     # Trial calls in HALF_OPEN (default: 1)
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Master switch (opt-in) |
| `failure-threshold` | `5` | Consecutive failures to trip the circuit |
| `reset-timeout-ms` | `30000` | Wait time before allowing trial calls |
| `half-open-max-calls` | `1` | Number of trial calls in HALF_OPEN |

---

## How It Works

### LLM Call Protection

When enabled, the circuit breaker wraps the `callWithRetry` method in `SpringAiAgentExecutor`. This means:

1. **CB check** happens first — if OPEN, the call is immediately rejected
2. **Retry logic** runs inside the CB — all retry attempts count as a single CB execution
3. If retries exhaust and the call still fails, the CB records a failure

```
Request → Circuit Breaker → [Retry with Backoff → LLM Call] → Response
                │
                └─ OPEN? → CircuitBreakerOpenException → CIRCUIT_BREAKER_OPEN error
```

### Error Code

When the circuit is open, the agent returns:
- `errorCode`: `CIRCUIT_BREAKER_OPEN`
- `errorMessage`: "Service temporarily unavailable due to repeated failures. Please try again later."

This can be customized via `ErrorMessageResolver`.

---

## Architecture

### CircuitBreaker Interface

```kotlin
interface CircuitBreaker {
    suspend fun <T> execute(block: suspend () -> T): T
    fun state(): CircuitBreakerState
    fun reset()
    fun metrics(): CircuitBreakerMetrics
}
```

### CircuitBreakerRegistry

Named circuit breakers are managed by `CircuitBreakerRegistry`:

```kotlin
val registry = CircuitBreakerRegistry(failureThreshold = 5)
val llmBreaker = registry.get("llm")          // lazy creation
val mcpBreaker = registry.get("mcp:weather")   // isolated per name
```

Each name gets its own independent circuit breaker. The executor uses `"llm"` by default.

### Key Design Decisions

- **Thread-safe**: Uses `AtomicReference`, `AtomicInteger`, `AtomicLong` (no locks)
- **CancellationException-safe**: Never counted as a failure (preserves structured concurrency)
- **Testable clock**: Accepts a `clock` function for deterministic time-based testing
- **No external dependencies**: Pure Kotlin implementation

---

## Custom Circuit Breaker

Override the default by providing your own bean:

```kotlin
@Bean
fun circuitBreakerRegistry(): CircuitBreakerRegistry {
    return CircuitBreakerRegistry(
        failureThreshold = 10,
        resetTimeoutMs = 60_000,
        halfOpenMaxCalls = 3
    )
}
```

Or implement the `CircuitBreaker` interface directly and pass it to `SpringAiAgentExecutor`.

---

## Metrics

Access circuit breaker metrics for monitoring:

```kotlin
val metrics = circuitBreakerRegistry.get("llm").metrics()
// CircuitBreakerMetrics(failureCount=3, successCount=42, state=CLOSED, lastFailureTime=1707...)
```

| Metric | Description |
|--------|-------------|
| `failureCount` | Current consecutive failure count |
| `successCount` | Total success count |
| `state` | Current state (CLOSED/OPEN/HALF_OPEN) |
| `lastFailureTime` | Epoch ms of last failure (null if none) |

---

## Interaction with Retry

The circuit breaker and retry work together:

```
Circuit Breaker
  └─ Retry (with exponential backoff)
       └─ LLM Call
```

- **Retry** handles transient errors within a single request (e.g., temporary 503)
- **Circuit breaker** handles persistent failures across multiple requests (e.g., provider outage)
- If all retry attempts fail, the circuit breaker records **one** failure
- If a retry succeeds, the circuit breaker records **one** success
