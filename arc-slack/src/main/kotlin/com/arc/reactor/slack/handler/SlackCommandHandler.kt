package com.arc.reactor.slack.handler

import com.arc.reactor.slack.model.SlackSlashCommand

/**
 * Interface for handling Slack slash commands.
 */
interface SlackCommandHandler {
    suspend fun handleSlashCommand(command: SlackSlashCommand)
}
