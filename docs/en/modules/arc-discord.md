# arc-discord

## Overview

arc-discord connects Arc Reactor to Discord using the Discord4J reactive library. It listens for message events on the Discord gateway, optionally filters to mention-only responses, enforces concurrency limits, and delegates to `AgentExecutor`. Responses are sent back to the originating channel.

The module uses Discord4J's WebSocket gateway (not webhooks), so no public URL is required.

## Activation

**Property:**
```yaml
arc:
  reactor:
    discord:
      enabled: true
      token: "${DISCORD_BOT_TOKEN}"
```

**Gradle dependency:**
```kotlin
implementation("com.arc.reactor:arc-discord")
```

An `AgentExecutor` bean must be present for the event handler to activate. The `GatewayDiscordClient` connects to Discord synchronously during application startup via `DiscordClientBuilder.create(token).build().login().block()`. If the token is invalid or Discord is unreachable, startup fails.

## Key Components

| Class | Role |
|---|---|
| `DiscordAutoConfiguration` | Wires all beans; starts `DiscordMessageListener` on `ApplicationReadyEvent` |
| `GatewayDiscordClient` | Discord4J gateway client (blocking login at startup) |
| `DiscordMessageListener` | Subscribes to `MessageCreateEvent`; filters bots and applies mention-only mode |
| `DefaultDiscordEventHandler` | Strips mention tags, maps channel to session, calls `AgentExecutor`, sends reply |
| `DiscordMessagingService` | Sends messages to Discord channels; splits responses exceeding Discord's 2000-character limit |
| `DiscordEventCommand` | Internal data class carrying channel ID, user ID, content, message ID, guild ID |

## Configuration

All properties are under the prefix `arc.reactor.discord`.

| Property | Default | Description |
|---|---|---|
| `enabled` | `false` | Activates the module |
| `token` | `""` | Discord bot token |
| `max-concurrent-requests` | `5` | Max simultaneous agent executions |
| `request-timeout-ms` | `30000` | Agent execution timeout (ms) |
| `respond-to-mentions-only` | `true` | Only respond when the bot is mentioned (`@BotName`) |

## Integration

**Agent execution:**

`DefaultDiscordEventHandler` calls `AgentExecutor.execute()` with:
- `systemPrompt` — hardcoded to "You are a helpful AI assistant responding in a Discord channel. Keep responses concise and well-formatted for Discord."
- `userId` — Discord user ID (Snowflake string)
- `sessionId` — `"discord-{channelId}"` (channel-scoped session; all messages in a channel share one session)
- `metadata` — `source=discord`

**Mention-only mode:**

When `respond-to-mentions-only=true` (default), `DiscordMessageListener` checks whether the bot's Snowflake ID is in the message's `userMentionIds`. Messages that do not mention the bot are silently ignored.

**Message splitting:**

Discord enforces a 2000-character message limit. `DiscordMessagingService` splits responses longer than 2000 characters into sequential messages to avoid rejected API calls.

**Concurrency control:**

`DiscordMessageListener` uses a Kotlin `Semaphore(maxConcurrentRequests)` with `semaphore.withPermit {}`. When all permits are acquired, incoming events wait until a slot is free (no drop/reject). Each handler runs in a `SupervisorJob`-scoped coroutine, so one failure does not cancel others.

**Startup sequence:**

The auto-configuration registers `DiscordMessageListener` as a bean, then an `@EventListener(ApplicationReadyEvent::class)` calls `listener.startListening()` after the Spring context is fully initialized. This prevents race conditions where the listener might receive events before other beans are ready.

## Code Examples

**Minimal configuration:**

```yaml
arc:
  reactor:
    discord:
      enabled: true
      token: "${DISCORD_BOT_TOKEN}"
```

**Respond to all messages in a channel (no mention required):**

```yaml
arc:
  reactor:
    discord:
      enabled: true
      token: "${DISCORD_BOT_TOKEN}"
      respond-to-mentions-only: false
```

**Custom event handler:**

```kotlin
@Component
class MyDiscordEventHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: DiscordMessagingService
) : DiscordEventHandler {

    override suspend fun handleMessage(command: DiscordEventCommand) {
        val result = agentExecutor.execute(
            AgentCommand(
                userPrompt = command.content,
                userId = command.userId,
                metadata = mapOf("guildId" to (command.guildId ?: "dm"))
            )
        )
        messagingService.sendMessage(command.channelId, result.content ?: "No response")
    }
}
```

## Common Pitfalls / Notes

**Discord login blocks during startup.** `DiscordClientBuilder.create(token).build().login().block()` is synchronous. If Discord's gateway is unreachable, application startup hangs until the connection times out.

**Session is channel-scoped, not user-scoped.** All users in the same channel share one `sessionId`. This means conversation history across users mixes in the same session. Override `DefaultDiscordEventHandler` with a user-scoped session key if isolation is needed.

**Bot messages are always ignored.** `DiscordMessageListener` calls `author.isBot` and returns immediately if true. This prevents the bot from responding to itself or other bots.

**2000-character limit applies to each split message.** If a response is very long, `DiscordMessagingService` sends it as multiple sequential messages. Discord does not have a native multi-part message concept; rapid sequential messages may be rate-limited.

**`respond-to-mentions-only=false` in busy servers.** Disabling mention-only mode causes the bot to respond to every message in every channel it can read, which can exhaust `maxConcurrentRequests` quickly.
