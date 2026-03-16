# Webhooks

## Overview

Arc Reactor can send HTTP POST notifications to an external URL after every agent execution. This is useful for integrating with monitoring systems, Slack, custom dashboards, or any service that accepts incoming webhooks.

Webhooks are implemented as an `AfterAgentCompleteHook` with **fail-open** semantics (order 200). A webhook failure never blocks or affects the agent response.

```
Request → Guard → Hook(BeforeStart) → [ReAct Loop] → Hook(AfterComplete) → Response
                                                              │
                                                     WebhookNotificationHook
                                                              │
                                                       POST → External URL
```

**Disabled by default** — opt-in via configuration.

---

## Configuration

```yaml
arc:
  reactor:
    webhook:
      enabled: true                        # Enable webhook notifications (default: false)
      url: https://example.com/webhook     # POST target URL
      timeout-ms: 5000                     # HTTP timeout in milliseconds (default: 5000)
      include-conversation: false          # Include full prompt/response in payload (default: false)
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Master switch. The hook bean is only registered when `true`. |
| `url` | `""` | HTTP endpoint to POST the payload to. Blank URL is a no-op. |
| `timeout-ms` | `5000` | Maximum time to wait for a response from the webhook endpoint. |
| `include-conversation` | `false` | When `true`, the full `userPrompt` and `fullResponse` are included in the payload. Disable in production to avoid PII leakage. |

---

## Payload Structure

Every webhook POST sends a JSON body with the following fields:

### Success payload

```json
{
  "event": "AGENT_COMPLETE",
  "timestamp": "2026-03-17T08:30:00.123Z",
  "runId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "userId": "user-42",
  "success": true,
  "toolsUsed": ["calculator", "web-search"],
  "durationMs": 2340,
  "contentPreview": "The answer to your question is 42. Here is the detailed explanation..."
}
```

### Failure payload

```json
{
  "event": "AGENT_COMPLETE",
  "timestamp": "2026-03-17T08:30:00.123Z",
  "runId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "userId": "user-42",
  "success": false,
  "toolsUsed": [],
  "durationMs": 150,
  "errorMessage": "Rate limit exceeded"
}
```

### Field reference

| Field | Type | Always present | Description |
|-------|------|----------------|-------------|
| `event` | `String` | Yes | Always `"AGENT_COMPLETE"` |
| `timestamp` | `String` | Yes | ISO-8601 instant (UTC) |
| `runId` | `String` | Yes | Unique execution ID (UUID) |
| `userId` | `String` | Yes | Requesting user's ID |
| `success` | `Boolean` | Yes | Whether the agent execution succeeded |
| `toolsUsed` | `List<String>` | Yes | Names of tools invoked during execution |
| `durationMs` | `Long` | Yes | Total execution duration in milliseconds |
| `contentPreview` | `String` | On success only | First 200 characters of the response |
| `errorMessage` | `String` | On failure only | Error description |
| `userPrompt` | `String` | Only if `include-conversation=true` | The user's original prompt |
| `fullResponse` | `String` | Only if `include-conversation=true` | The complete agent response |

---

## Fail-Open Design

The webhook hook is designed to never interfere with agent execution:

1. **`failOnError = false`** — exceptions in the hook are logged and swallowed
2. **`order = 200`** — runs late, after all critical hooks (authentication, billing, audit)
3. **HTTP timeout** — prevents hanging on slow or unresponsive endpoints
4. **Reactive error handling** — `onErrorResume` catches WebClient errors before they propagate
5. **CancellationException safety** — always rethrown before generic exception handling to preserve structured concurrency

Even if the webhook endpoint is down, returns an error, or times out, the agent response is delivered to the caller unchanged.

---

## Architecture

### Class hierarchy

```
AfterAgentCompleteHook (interface)
  └── WebhookNotificationHook
        ├── order = 200
        ├── failOnError = false
        └── uses WebClient for non-blocking HTTP POST
```

### Auto-configuration

The `WebhookNotificationHook` bean is registered in `ArcReactorHookAndMcpConfiguration` with:

- `@ConditionalOnMissingBean` — users can override with their own implementation
- `@ConditionalOnProperty(prefix = "arc.reactor.webhook", name = ["enabled"], havingValue = "true")` — only created when explicitly enabled

Properties are read from `AgentProperties.webhook` (`WebhookConfigProperties` data class) and mapped to the internal `WebhookProperties` used by the hook.

---

## Integration Examples

### Slack incoming webhook

Point the webhook URL at a Slack incoming webhook endpoint:

```yaml
arc:
  reactor:
    webhook:
      enabled: true
      url: https://your-webhook-endpoint.example.com/arc-reactor
      timeout-ms: 3000
```

Slack expects a specific payload format, so you would need a custom hook to format the message. See [Custom webhook handler](#custom-webhook-handler) below.

### Monitoring dashboard

Send completion events to a metrics collector:

```yaml
arc:
  reactor:
    webhook:
      enabled: true
      url: https://monitoring.internal.example.com/api/agent-events
      timeout-ms: 5000
      include-conversation: false    # Never send prompt/response to monitoring
```

### PagerDuty / alerting integration

Use a middleware endpoint that checks `success=false` and routes to your alerting system:

```yaml
arc:
  reactor:
    webhook:
      enabled: true
      url: https://events.pagerduty.com/generic/2010-04-15/create_event.json
      timeout-ms: 5000
```

---

## Custom Webhook Handler

To customize the payload format (e.g., Slack Block Kit, Microsoft Teams Adaptive Card), override the default bean:

```kotlin
@Component
class SlackWebhookNotificationHook(
    private val webhookProperties: WebhookProperties,
    private val webClient: WebClient = WebClient.create()
) : AfterAgentCompleteHook {

    override val order: Int = 200
    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        val slackPayload = mapOf(
            "text" to buildSlackMessage(context, response)
        )

        try {
            webClient.post()
                .uri(webhookProperties.url)
                .bodyValue(slackPayload)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofMillis(webhookProperties.timeoutMs))
                .onErrorResume { e ->
                    e.throwIfCancellation()
                    logger.warn { "Slack webhook failed: ${e.message}" }
                    Mono.empty()
                }
                .awaitSingleOrNull()
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "Slack webhook error: ${e.message}" }
        }
    }

    private fun buildSlackMessage(context: HookContext, response: AgentResponse): String {
        val status = if (response.success) "Success" else "Failed"
        val tools = response.toolsUsed.joinToString(", ").ifEmpty { "none" }
        return ":robot_face: Agent $status | runId=${context.runId} | " +
            "tools=[$tools] | ${response.totalDurationMs}ms"
    }
}
```

Because `@ConditionalOnMissingBean` is used on the default hook, your custom `@Component` takes precedence automatically.

---

## Common Pitfalls

| Pitfall | Explanation |
|---------|-------------|
| **Blank URL is silent** | If `url` is blank, the hook logs a warning and returns immediately. No exception is thrown. |
| **PII in payloads** | When `include-conversation=true`, the full prompt and response are sent over the network. Use HTTPS and disable this in production unless the endpoint is trusted. |
| **Slow endpoints** | A slow webhook does not block the agent response, but it does hold a coroutine until the timeout expires. Keep `timeout-ms` low (3000-5000ms). |
| **CancellationException** | Always rethrow `CancellationException` before generic `Exception` in any custom webhook hook. Use `throwIfCancellation()` from Arc Reactor's support package. |
| **No retry** | The default implementation does not retry failed webhook deliveries. If at-least-once delivery is required, implement a custom hook with a queue or use a reliable message broker. |
