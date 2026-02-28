# arc-slack

## Overview

arc-slack connects Arc Reactor to Slack. It handles incoming messages (Events API and Socket Mode), slash commands, request signature verification, and backpressure limiting, then delegates to `AgentExecutor` and sends the agent response back to the originating Slack channel or thread.

Key capabilities:

- **Dual transport** — Socket Mode (WebSocket, no public URL required) or Events API (HTTP callbacks); configured per deployment
- **Thread-aware sessions** — Slack thread timestamps are mapped to `sessionId` values, so conversation history is maintained within a thread
- **HMAC-SHA256 signature verification** — all incoming requests are verified against the Slack signing secret with timing-safe comparison and replay-attack protection (5-minute tolerance window)
- **Backpressure limiting** — a semaphore-based limiter drops or queues events when `maxConcurrentRequests` are in flight; fail-fast mode rejects immediately instead of queuing
- **Event deduplication** — in-memory LRU cache of `event_id` values prevents duplicate processing under at-least-once delivery
- **Retry on Slack API errors** — `SlackMessagingService` retries `429` and `5xx` responses with configurable back-off
- **Micrometer integration** — optional metrics via `MicrometerSlackMetricsRecorder` when `MeterRegistry` is present

## Activation

**Property:**
```yaml
arc:
  reactor:
    slack:
      enabled: true
      bot-token: "xoxb-..."
      signing-secret: "..."
      # For Socket Mode (default):
      app-token: "xapp-..."
      transport-mode: SOCKET_MODE
```

**Gradle dependency:**
```kotlin
implementation("com.arc.reactor:arc-slack")
```

An `AgentExecutor` bean must be present for the event and command handlers to activate. Without it, only the messaging service and signature verifier are registered.

## Slack Local Tools

`arc-slack` now also provides Slack tools directly as Arc Reactor `LocalTool` beans (no external Slack MCP server required).

Enable with:

```yaml
arc:
  reactor:
    slack:
      tools:
        enabled: true
        bot-token: "${SLACK_BOT_TOKEN}"
        tool-exposure:
          scope-aware-enabled: true
          fail-open-on-scope-resolution-error: true
```

When enabled, 11 tools are registered:
- `send_message`
- `reply_to_thread`
- `list_channels`
- `find_channel`
- `read_messages`
- `read_thread_replies`
- `add_reaction`
- `get_user_info`
- `find_user`
- `search_messages`
- `upload_file`

If `tool-exposure.scope-aware-enabled=true`, tool visibility is filtered by granted Slack OAuth scopes.

## Key Components

| Class | Role |
|---|---|
| `SlackAutoConfiguration` | Wires all beans; conditionally creates Socket Mode or Events API beans |
| `SlackSocketModeGateway` | `SmartLifecycle` bean that maintains the WebSocket connection with exponential-backoff reconnect |
| `SlackEventController` | HTTP endpoint for Events API callbacks (`POST /slack/events`) |
| `SlackCommandController` | HTTP endpoint for slash commands (`POST /slack/commands`) |
| `SlackEventProcessor` | Applies deduplication and backpressure, then dispatches to `SlackEventHandler` |
| `SlackCommandProcessor` | Applies backpressure, then dispatches to `SlackCommandHandler` |
| `SlackBackpressureLimiter` | Semaphore-based concurrency guard; fail-fast or queue mode |
| `SlackEventDeduplicator` | In-memory LRU-style cache of processed `event_id` values |
| `DefaultSlackEventHandler` | Strips bot mention tags, maps thread to session, calls `AgentExecutor`, sends reply |
| `DefaultSlackCommandHandler` | Handles slash commands; sends immediate ack, then processes via agent |
| `SlackMessagingService` | Wraps Slack Web API (`chat.postMessage`, `chat.postEphemeral`, response URLs) with retry |
| `SlackSignatureVerifier` | HMAC-SHA256 verification with timing-safe comparison |
| `SlackSignatureWebFilter` | WebFlux filter that validates all requests before they reach controllers (Events API only) |
| `SlackSystemPromptFactory` | Builds the system prompt injected into every Slack-originated agent call |
| `SlackMetricsRecorder` | Interface for recording Slack-specific metrics (NoOp or Micrometer implementation) |

## Configuration

All properties are under the prefix `arc.reactor.slack`.

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Activates the module |
| `transport-mode` | `SOCKET_MODE` | `SOCKET_MODE` or `EVENTS_API` |
| `bot-token` | `""` | Slack Bot User OAuth Token (`xoxb-...`) |
| `app-token` | `""` | Slack App-Level Token for Socket Mode (`xapp-...`) |
| `signing-secret` | `""` | Slack signing secret for signature verification |
| `signature-verification-enabled` | `true` | Enable HMAC-SHA256 request verification |
| `timestamp-tolerance-seconds` | `300` | Max clock skew allowed for signature verification |
| `max-concurrent-requests` | `5` | Max simultaneous agent executions |
| `request-timeout-ms` | `30000` | Agent execution timeout (ms) |
| `fail-fast-on-saturation` | `true` | When at capacity, reject immediately instead of queuing |
| `notify-on-drop` | `false` | Send a "busy" message to Slack when events are dropped |
| `api-max-retries` | `2` | Retry attempts for Slack API on `429`/`5xx` |
| `api-retry-default-delay-ms` | `1000` | Default retry delay when `Retry-After` is absent |
| `event-dedup-enabled` | `true` | Enable in-memory event deduplication |
| `event-dedup-ttl-seconds` | `600` | Dedup cache TTL (seconds) |
| `event-dedup-max-entries` | `10000` | Max entries in the dedup cache |
| `socket-backend` | `JAVA_WEBSOCKET` | WebSocket backend: `JAVA_WEBSOCKET` or `TYRUS` |
| `socket-connect-retry-initial-delay-ms` | `1000` | Initial delay for Socket Mode reconnect |
| `socket-connect-retry-max-delay-ms` | `30000` | Maximum delay for Socket Mode reconnect |

## Integration

**Agent execution:**

`DefaultSlackEventHandler` calls `AgentExecutor.execute()` with:
- `systemPrompt` — built by `SlackSystemPromptFactory` (references the configured LLM provider)
- `userId` — Slack user ID
- `sessionId` — `"slack-{channelId}-{threadTs}"` (thread-scoped conversation memory)
- `metadata` — `source=slack`, `channel=slack`

**Thread-to-session mapping:**

Slack thread timestamps (`thread_ts`) become `sessionId` values. Replies in the same thread reuse the same session, giving the agent access to the full conversation history.

**Signature verification (Events API):**

`SlackSignatureWebFilter` intercepts all requests to Slack endpoints, reads the raw body, computes `v0=HMAC-SHA256(signingSecret, "v0:{timestamp}:{body}")`, and compares using `MessageDigest.isEqual` (timing-safe). Requests older than `timestamp-tolerance-seconds` are rejected regardless of signature.

**Socket Mode lifecycle:**

`SlackSocketModeGateway` implements `SmartLifecycle` and starts after the application context is ready. It connects with exponential backoff on failure, auto-reconnects on disconnect, and registers listeners for Events API envelopes, slash command envelopes, and interactive payloads. Interactive payloads are logged and dropped (not supported).

## Code Examples

**Minimal configuration (Socket Mode):**

```yaml
arc:
  reactor:
    slack:
      enabled: true
      bot-token: "${SLACK_BOT_TOKEN}"
      app-token: "${SLACK_APP_TOKEN}"
      signing-secret: "${SLACK_SIGNING_SECRET}"
```

**Events API configuration (requires a public URL):**

```yaml
arc:
  reactor:
    slack:
      enabled: true
      transport-mode: EVENTS_API
      bot-token: "${SLACK_BOT_TOKEN}"
      signing-secret: "${SLACK_SIGNING_SECRET}"
      max-concurrent-requests: 10
      fail-fast-on-saturation: false
      notify-on-drop: true
```

**Custom event handler:**

```kotlin
@Component
class MySlackEventHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: SlackMessagingService
) : SlackEventHandler {

    override suspend fun handleAppMention(command: SlackEventCommand) {
        val result = agentExecutor.execute(
            AgentCommand(userPrompt = command.text, userId = command.userId)
        )
        messagingService.sendMessage(
            channelId = command.channelId,
            text = result.content ?: "No response",
            threadTs = command.threadTs
        )
    }

    override suspend fun handleMessage(command: SlackEventCommand) {
        // Direct messages — handle as needed
    }
}
```

## Common Pitfalls / Notes

**Socket Mode requires `app-token`.** If `transport-mode=SOCKET_MODE` and `app-token` is blank, `SlackSocketModeGateway.start()` throws `IllegalStateException` immediately.

**Signature verification only applies to Events API.** In Socket Mode, Slack handles authentication at the WebSocket level. `SlackSignatureWebFilter` is only registered when `transport-mode=events_api`.

**`notify-on-drop=true` can amplify load.** When the system is overloaded, sending "I'm busy" messages to Slack requires additional API calls, which consumes capacity and can worsen the situation. Keep it `false` in high-load environments.

**Deduplication is in-memory.** The dedup cache is not shared across instances. In a horizontally scaled deployment, the same event may be processed once per instance. Use an external dedup store (Redis, DB) if exactly-once semantics are required.

**`event-dedup-ttl-seconds` should exceed Slack's retry window.** Slack retries failed Events API deliveries for several minutes. The default of 600 seconds covers the typical retry window.

**Interactive payloads are not supported.** Block Kit button clicks, modal submissions, and similar interactive payloads are acknowledged and dropped. Override the listener in `SlackSocketModeGateway` or handle them at the Events API level if needed.
