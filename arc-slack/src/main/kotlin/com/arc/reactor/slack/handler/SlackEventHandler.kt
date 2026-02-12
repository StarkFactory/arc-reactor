package com.arc.reactor.slack.handler

import com.arc.reactor.slack.model.SlackEventCommand

/**
 * Interface for handling Slack events.
 *
 * Implement this interface to customize how Slack events (mentions, messages)
 * are processed. Register as a bean to override the default behavior.
 */
interface SlackEventHandler {

    /**
     * Handles an @mention event (app_mention).
     * Called when a user mentions the bot in a channel or DM.
     */
    suspend fun handleAppMention(command: SlackEventCommand)

    /**
     * Handles a message event in a thread where the bot is participating.
     * Called for thread replies after the initial mention.
     */
    suspend fun handleMessage(command: SlackEventCommand)
}
