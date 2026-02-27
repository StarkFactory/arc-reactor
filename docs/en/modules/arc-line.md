# arc-line

## Overview

arc-line connects Arc Reactor to the LINE Messaging API. It receives webhook events from LINE, verifies HMAC-SHA256 signatures, and dispatches messages to `AgentExecutor`. Responses are sent using LINE's reply API first; if the reply token has expired, it falls back to the push API.

The module handles one-on-one chats, group chats, and room chats, mapping each source type to an appropriate session ID and reply target.

## Activation

**Property:**
```yaml
arc:
  reactor:
    line:
      enabled: true
      channel-token: "${LINE_CHANNEL_TOKEN}"
      channel-secret: "${LINE_CHANNEL_SECRET}"
```

**Gradle dependency:**
```kotlin
implementation("com.arc.reactor:arc-line")
```

An `AgentExecutor` bean must be present for the event handler to activate.

## Key Components

| Class | Role |
|---|---|
| `LineAutoConfiguration` | Wires all beans |
| `LineWebhookController` | Receives webhook payloads at `POST /line/webhook` |
| `LineSignatureVerifier` | Verifies `X-Line-Signature` using `Base64(HMAC-SHA256(channelSecret, body))` |
| `LineSignatureWebFilter` | WebFlux filter that rejects requests with invalid signatures |
| `DefaultLineEventHandler` | Maps source type to session/target, calls `AgentExecutor`, sends response |
| `LineMessagingService` | Wraps LINE Messaging API: `replyMessage` and `pushMessage` |
| `LineEventCommand` | Internal data class carrying user ID, text, reply token, and source context |

## Configuration

All properties are under the prefix `arc.reactor.line`.

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Activates the module |
| `channel-token` | `""` | LINE Channel Access Token for Messaging API |
| `channel-secret` | `""` | LINE Channel Secret for signature verification |
| `signature-verification-enabled` | `true` | Enable HMAC-SHA256 webhook signature verification |
| `max-concurrent-requests` | `5` | Max simultaneous agent executions |
| `request-timeout-ms` | `30000` | Agent execution timeout (ms) |

## Integration

**Agent execution:**

`DefaultLineEventHandler` calls `AgentExecutor.execute()` with:
- `systemPrompt` — "You are a helpful AI assistant responding on LINE. Keep responses concise."
- `userId` — LINE user ID
- `sessionId` — derived from source type: `"line-{groupId}"` for group, `"line-{roomId}"` for room, `"line-{userId}"` for one-on-one
- `metadata` — `source=line`

**Reply vs. push fallback:**

LINE reply tokens are single-use and expire shortly after the webhook is delivered. `DefaultLineEventHandler` tries `LineMessagingService.replyMessage(replyToken, text)` first. If it returns `false` (expired or failed), it falls back to `LineMessagingService.pushMessage(to, text)`, where `to` is the group ID, room ID, or user ID depending on the source type.

**Signature verification:**

LINE uses `Base64(HMAC-SHA256(channelSecret, body))` — note that unlike Slack, there is no timestamp in the signature. `LineSignatureVerifier.verify()` computes the expected signature and compares it using `MessageDigest.isEqual` (timing-safe). `LineSignatureWebFilter` applies this check to all webhook requests before they reach the controller.

**Session scoping:**

| Source type | Session key | Reply target |
|---|---|---|
| `user` (1-on-1) | `line-{userId}` | `userId` |
| `group` | `line-{groupId}` | `groupId` |
| `room` | `line-{roomId}` | `roomId` |

## Code Examples

**Minimal configuration:**

```yaml
arc:
  reactor:
    line:
      enabled: true
      channel-token: "${LINE_CHANNEL_TOKEN}"
      channel-secret: "${LINE_CHANNEL_SECRET}"
```

**Custom event handler:**

```kotlin
@Component
class MyLineEventHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: LineMessagingService
) : LineEventHandler {

    override suspend fun handleMessage(command: LineEventCommand) {
        val result = agentExecutor.execute(
            AgentCommand(userPrompt = command.text, userId = command.userId)
        )
        val text = result.content ?: "No response"
        val replied = messagingService.replyMessage(command.replyToken, text)
        if (!replied) {
            messagingService.pushMessage(command.userId, text)
        }
    }
}
```

## Common Pitfalls / Notes

**Reply tokens expire quickly.** LINE reply tokens are valid for a short window after webhook delivery (typically under 60 seconds). If agent execution takes longer than this, `replyMessage` will fail and the push fallback will be used. Push requires a channel token with sufficient scope; ensure it is configured correctly.

**Signature verification has no timestamp.** Unlike Slack, LINE does not include a timestamp in the signature. There is no replay-attack protection beyond HTTPS. LINE's own infrastructure is expected to send events only once.

**Push API is quota-limited.** The LINE Messaging API push endpoint has a monthly message quota on free plans. Relying heavily on the push fallback can exhaust this quota. For high-throughput deployments, ensure agent response times stay within the reply token validity window.

**Webhook URL must be registered in the LINE Developer Console.** Set the webhook URL to `https://your-domain/line/webhook` and enable webhook delivery in the channel settings.
