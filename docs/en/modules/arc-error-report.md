# arc-error-report

## Overview

arc-error-report provides an HTTP endpoint that receives production error reports and triggers autonomous AI-powered root cause analysis. The analysis agent uses registered tools (MCP servers and/or local tools) to investigate the error across the repository, issue tracker, documentation, and messaging systems, then sends a formatted incident report to Slack.

The endpoint accepts the report and returns immediately (202-style async processing). The agent runs in the background using a semaphore to bound concurrency.

## Activation

**Property:**
```yaml
arc:
  reactor:
    error-report:
      enabled: true
```

**Gradle dependency:**
```kotlin
implementation("com.arc.reactor:arc-error-report")
```

An `AgentExecutor` bean must be present for the handler to activate.

## Key Components

| Class | Role |
|---|---|
| `ErrorReportAutoConfiguration` | Wires `ErrorReportHandler` bean |
| `ErrorReportController` | `POST /api/error-report` — validates API key, truncates stack trace, dispatches async |
| `DefaultErrorReportHandler` | Builds prompt, calls `AgentExecutor` with the error analysis system prompt |
| `ErrorReportHandler` | Interface for custom handler implementations |
| `ErrorReportRequest` | Incoming payload: `stackTrace`, `serviceName`, `repoSlug`, `slackChannel`, plus optional fields |
| `ErrorReportResponse` | Immediate response: `{ "accepted": true, "requestId": "..." }` |

## Configuration

All properties are under the prefix `arc.reactor.error-report`.

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Activates the module |
| `api-key` | `""` | API key required in `X-API-Key` header. Blank = no auth |
| `max-concurrent-requests` | `3` | Max simultaneous error analyses running concurrently |
| `request-timeout-ms` | `120000` | Agent execution timeout for error analysis (ms) |
| `max-tool-calls` | `25` | Max tool calls the error analysis agent may make |
| `max-stack-trace-length` | `30000` | Stack trace truncation limit (chars) |

## Integration

**Agent execution:**

`DefaultErrorReportHandler` calls `AgentExecutor.execute()` with a fixed system prompt that instructs the agent to:

1. Clone or locate the repository via repository tools (commonly MCP-backed)
2. Load and index the repository, then analyze the stack trace using error analysis tools
3. Examine specific files and search for error patterns
4. Search Jira for related issues and identify responsible developers
5. Search Confluence for relevant runbooks
6. Compose and send a formatted Slack message to the specified channel (prefer built-in Slack local tools when available)

The user prompt is constructed from the incoming `ErrorReportRequest` fields: service name, repository slug, Slack channel, environment, timestamp, metadata, and the full stack trace.

**Tool dependency:**

The analysis quality depends on which tools are available at runtime. Repository/Jira/Confluence/error-analysis tools are commonly provided via MCP servers. Slack delivery can be handled by built-in `arc-slack` local tools (`send_message`, `reply_to_thread`) without any external Slack adapter. If a tool category is unavailable, the agent skips it and continues with whatever tools are accessible.

**Async processing:**

`ErrorReportController` fires the analysis in a `SupervisorJob`-scoped coroutine and responds immediately with `{ "accepted": true, "requestId": "..." }`. The caller does not wait for analysis to complete. Concurrency is bounded by a `Semaphore(maxConcurrentRequests)`.

**API key authentication:**

If `api-key` is non-blank, all requests must include `X-API-Key: {api-key}`. Requests with a missing or incorrect key receive `401 Unauthorized` with an error body. If `api-key` is blank, authentication is skipped.

## Code Examples

**Minimal configuration:**

```yaml
arc:
  reactor:
    error-report:
      enabled: true
      api-key: "${ERROR_REPORT_API_KEY}"
      max-tool-calls: 30
      request-timeout-ms: 180000
```

**Submitting an error report:**

```bash
curl -X POST http://localhost:8080/api/error-report \
  -H "Content-Type: application/json" \
  -H "X-API-Key: secret-key" \
  -d '{
    "stackTrace": "java.lang.NullPointerException: Cannot read field \"id\"...\n\tat com.example.Service.process(Service.java:42)",
    "serviceName": "payment-service",
    "repoSlug": "payment-service",
    "slackChannel": "#incidents",
    "environment": "production",
    "timestamp": "2026-02-28T10:00:00Z"
  }'
```

**Response:**
```json
{
  "accepted": true,
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Custom handler:**

```kotlin
@Component
class MyErrorReportHandler(
    private val agentExecutor: AgentExecutor,
    private val properties: ErrorReportProperties
) : ErrorReportHandler {

    override suspend fun handle(requestId: String, request: ErrorReportRequest) {
        // Custom analysis logic — call agentExecutor or other tools
        val result = agentExecutor.execute(
            AgentCommand(
                systemPrompt = "Custom error analysis prompt",
                userPrompt = "Service: ${request.serviceName}\n${request.stackTrace}",
                maxToolCalls = properties.maxToolCalls
            )
        )
        // Send result to your preferred channel
    }
}
```

## Common Pitfalls / Notes

**Required tools must be available at runtime.** Repository/Jira/Confluence/error-analysis tools are typically registered through `POST /api/mcp/servers`. Slack sending uses `arc-slack` local tools when enabled. If required tools are missing, the agent falls back to partial analysis and may not be able to deliver a Slack report.

**The endpoint returns 200 immediately, not when analysis is done.** Callers should not poll for a result — there is no result endpoint. Monitor the Slack channel specified in the request for the analysis output.

**Stack traces are truncated.** If the submitted stack trace exceeds `max-stack-trace-length` characters, it is truncated at that boundary with `\n... [truncated]` appended. Reduce verbosity in the submitter if important frames are being cut off.

**`max-tool-calls` affects analysis depth.** Deep repository analysis (clone → index → analyze → search → cross-reference) can consume 10–20 tool calls. Setting `max-tool-calls` too low will cause the agent to stop before sending the Slack report. The default of 25 is a reasonable minimum.

**`request-timeout-ms` covers the entire agent loop.** The 120-second default includes all tool calls. For large repositories or slow Jira/Confluence instances, increase this to avoid truncated analyses.
