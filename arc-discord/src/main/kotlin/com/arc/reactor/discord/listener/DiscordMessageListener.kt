package com.arc.reactor.discord.listener

import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.discord.config.DiscordProperties
import com.arc.reactor.discord.handler.DiscordEventHandler
import com.arc.reactor.discord.model.DiscordEventCommand
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.message.MessageCreateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Listens for Discord message events and dispatches them to the event handler.
 *
 * Features:
 * - Filters out bot messages
 * - Optional mention-only mode (respondToMentionsOnly)
 * - Concurrency control via Semaphore
 * - SupervisorJob ensures one failure does not cancel other handlers
 */
class DiscordMessageListener(
    private val client: GatewayDiscordClient,
    private val handler: DiscordEventHandler,
    private val properties: DiscordProperties
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val semaphore = Semaphore(properties.maxConcurrentRequests)
    private val selfId: String by lazy {
        client.selfId.asString()
    }

    /**
     * Starts listening for MessageCreateEvent from the Discord gateway.
     * Called on ApplicationReadyEvent by the auto-configuration.
     */
    fun startListening() {
        client.on(MessageCreateEvent::class.java)
            .subscribe { event ->
                try {
                    processEvent(event)
                } catch (e: Exception) {
                    logger.error(e) { "Error dispatching Discord event" }
                }
            }

        logger.info { "Discord message listener started (mentionsOnly=${properties.respondToMentionsOnly})" }
    }

    private fun processEvent(event: MessageCreateEvent) {
        val message = event.message
        val author = message.author.orElse(null) ?: return

        // Ignore bot messages
        if (author.isBot) return

        // If mention-only mode, check if bot is mentioned
        if (properties.respondToMentionsOnly) {
            val mentionedIds = message.userMentionIds
            if (!mentionedIds.contains(client.selfId)) return
        }

        val command = DiscordEventCommand(
            channelId = message.channelId.asString(),
            userId = author.id.asString(),
            username = author.username,
            content = message.content,
            messageId = message.id.asString(),
            guildId = message.guildId.orElse(null)?.asString()
        )

        scope.launch {
            semaphore.withPermit {
                try {
                    handler.handleMessage(command)
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    logger.error(e) {
                        "Failed to handle Discord message=${command.messageId} " +
                            "from user=${command.userId}"
                    }
                }
            }
        }
    }
}
