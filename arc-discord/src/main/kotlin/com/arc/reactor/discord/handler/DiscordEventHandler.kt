package com.arc.reactor.discord.handler

import com.arc.reactor.discord.model.DiscordEventCommand

/**
 * Interface for handling Discord events.
 *
 * Implement this interface to customize how Discord messages are processed.
 * Register as a bean to override the default behavior.
 */
interface DiscordEventHandler {

    /**
     * Handles an incoming Discord message event.
     * Called when a user sends a message that the bot should respond to.
     */
    suspend fun handleMessage(command: DiscordEventCommand)
}
