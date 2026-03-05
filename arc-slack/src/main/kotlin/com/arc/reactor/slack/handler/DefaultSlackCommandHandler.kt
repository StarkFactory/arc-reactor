package com.arc.reactor.slack.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.slack.service.SlackUserEmailResolver
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default slash command handler.
 *
 * Flow:
 * 1. Post user question to the channel to create a thread
 * 2. Execute agent and reply in the created thread
 * 3. If channel post fails, fallback to response_url
 */
class DefaultSlackCommandHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: SlackMessagingService,
    private val defaultProvider: String = "configured backend model",
    private val threadTracker: SlackThreadTracker? = null,
    private val reminderStore: SlackReminderStore? = null,
    private val userEmailResolver: SlackUserEmailResolver? = null,
    private val mcpManager: McpManager? = null
) : SlackCommandHandler {

    override suspend fun handleSlashCommand(command: SlackSlashCommand) {
        val rawPrompt = command.text.trim()
        if (rawPrompt.isBlank()) {
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                text = "Please enter a question. Example: /jarvis What are my tasks today?"
            )
            return
        }

        try {
            when (val intent = SlackSlashIntentParser.parse(rawPrompt)) {
                is SlackSlashIntent.ReminderAdd -> {
                    handleReminderAdd(command, intent)
                    return
                }

                SlackSlashIntent.ReminderList -> {
                    handleReminderList(command)
                    return
                }

                is SlackSlashIntent.ReminderDone -> {
                    handleReminderDone(command, intent)
                    return
                }

                SlackSlashIntent.ReminderClear -> {
                    handleReminderClear(command)
                    return
                }

                is SlackSlashIntent.Agent -> {
                    handleAgentIntent(command, intent, rawPrompt)
                    return
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Failed to process slash command for channel=${command.channelId}" }
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                text = ":x: An internal error occurred. Please try again later."
            )
        }
    }

    private suspend fun handleAgentIntent(
        command: SlackSlashCommand,
        intent: SlackSlashIntent.Agent,
        originalPrompt: String
    ) {
        val threadTs = postQuestionToChannel(command)
        if (threadTs != null) {
            executeAndReplyInThread(command, intent, originalPrompt, threadTs)
        } else {
            executeAndReplyByResponseUrl(command, intent, originalPrompt)
        }
    }

    private suspend fun postQuestionToChannel(command: SlackSlashCommand): String? {
        val question = ":speech_balloon: *<@${command.userId}>'s question*\n>${command.text.trim()}"
        val result = messagingService.sendMessage(
            channelId = command.channelId,
            text = question
        )
        if (!result.ok) {
            logger.info { "Slash command channel post failed, fallback to response_url: error=${result.error}" }
            return null
        }
        return result.ts
    }
    private suspend fun executeAndReplyInThread(
        command: SlackSlashCommand,
        intent: SlackSlashIntent.Agent,
        originalPrompt: String,
        threadTs: String
    ) {
        threadTracker?.track(command.channelId, threadTs)
        val sessionId = "slack-${command.channelId}-$threadTs"
        val result = executeAgent(command, intent, sessionId)
        val responseText = SlackResponseTextFormatter.fromResult(result, originalPrompt)

        val sendResult = messagingService.sendMessage(
            channelId = command.channelId,
            text = responseText,
            threadTs = threadTs
        )

        if (!sendResult.ok) {
            logger.warn { "Thread reply failed, fallback to response_url: error=${sendResult.error}" }
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                text = responseText
            )
        }
    }

    private suspend fun executeAndReplyByResponseUrl(
        command: SlackSlashCommand,
        intent: SlackSlashIntent.Agent,
        originalPrompt: String
    ) {
        val sessionId = "slack-cmd-${command.channelId}-${command.userId}-${System.currentTimeMillis()}"
        val result = executeAgent(command, intent, sessionId)
        val responseText = SlackResponseTextFormatter.fromResult(result, originalPrompt)

        val sent = messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            text = responseText
        )
        if (!sent) {
            logger.error { "Failed to send slash response via response_url" }
        }
    }

    private suspend fun executeAgent(
        command: SlackSlashCommand,
        intent: SlackSlashIntent.Agent,
        sessionId: String
    ): com.arc.reactor.agent.model.AgentResult {
        val requesterEmail = resolveRequesterEmail(command.userId)
        val metadata = mutableMapOf<String, Any>(
            "sessionId" to sessionId,
            "source" to "slack",
            "channel" to "slack",
            "entrypoint" to "slash",
            "channelId" to command.channelId,
            "intent" to intent.mode.name.lowercase()
        )
        if (!requesterEmail.isNullOrBlank()) {
            metadata["requesterEmail"] = requesterEmail
            metadata["slackUserEmail"] = requesterEmail
            metadata["userEmail"] = requesterEmail
        }

        return agentExecutor.execute(
            AgentCommand(
                systemPrompt = SlackSystemPromptFactory.build(
                    defaultProvider, buildToolSummary()
                ),
                userPrompt = intent.prompt,
                userId = command.userId,
                metadata = metadata
            )
        )
    }

    private fun buildToolSummary(): String? {
        val manager = mcpManager ?: return null
        val toolsByServer = manager.listServers()
            .filter { manager.getStatus(it.name)?.name == "CONNECTED" }
            .associate { server ->
                server.name to manager.getToolCallbacks(server.name).map { it.name }
            }
            .filter { it.value.isNotEmpty() }
        return SlackSystemPromptFactory.buildToolSummary(toolsByServer)
    }

    private suspend fun handleReminderAdd(command: SlackSlashCommand, intent: SlackSlashIntent.ReminderAdd) {
        val store = reminderStore ?: return sendReminderUnavailable(command)
        val reminder = store.add(command.userId, intent.text)
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = "Saved reminder #${reminder.id}: ${reminder.text}"
        )
    }

    private suspend fun handleReminderList(command: SlackSlashCommand) {
        val store = reminderStore ?: return sendReminderUnavailable(command)
        val reminders = store.list(command.userId)
        if (reminders.isEmpty()) {
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                responseType = "ephemeral",
                text = "No saved reminders. Try: /jarvis remind Follow up with design review at 3pm"
            )
            return
        }
        val body = reminders.joinToString(separator = "\n") { "- #${it.id} ${it.text}" }
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = "Your reminders:\n$body"
        )
    }
    private suspend fun handleReminderDone(command: SlackSlashCommand, intent: SlackSlashIntent.ReminderDone) {
        val store = reminderStore ?: return sendReminderUnavailable(command)
        val reminder = store.done(command.userId, intent.id)
        val text = if (reminder != null) {
            "Completed reminder #${reminder.id}: ${reminder.text}"
        } else {
            "Reminder #${intent.id} was not found. Use /jarvis remind list."
        }
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = text
        )
    }

    private suspend fun handleReminderClear(command: SlackSlashCommand) {
        val store = reminderStore ?: return sendReminderUnavailable(command)
        val removed = store.clear(command.userId)
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = "Cleared $removed reminder(s)."
        )
    }

    private suspend fun sendReminderUnavailable(command: SlackSlashCommand) {
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = "Reminder feature is temporarily unavailable. Please try again later."
        )
    }

    private suspend fun resolveRequesterEmail(userId: String): String? {
        val resolver = userEmailResolver ?: return null
        return try {
            resolver.resolveEmail(userId)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to resolve Slack requester email for userId=$userId" }
            null
        }
    }
}
