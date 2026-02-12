package com.arc.reactor.line.handler

import com.arc.reactor.line.model.LineEventCommand

/**
 * Interface for handling LINE webhook events.
 *
 * Implement this interface to customize how LINE message events
 * are processed. Register as a bean to override the default behavior.
 */
interface LineEventHandler {

    /**
     * Handles a text message event from LINE.
     * Called when a user sends a text message to the bot.
     */
    suspend fun handleMessage(command: LineEventCommand)
}
