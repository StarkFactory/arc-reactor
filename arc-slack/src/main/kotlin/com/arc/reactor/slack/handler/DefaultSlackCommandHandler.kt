package com.arc.reactor.slack.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.slack.service.SlackUserEmailResolver
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 기본 슬래시 명령 핸들러.
 *
 * 흐름:
 * 1. 사용자 질문을 채널에 게시하여 스레드를 생성
 * 2. 에이전트를 실행하고 생성된 스레드에 답장
 * 3. 채널 게시 실패 시 response_url로 폴백
 *
 * 내장 인텐트:
 * - `help`: 도움말 표시
 * - `brief`: 일일 브리핑 생성
 * - `my-work`: 업무 현황 요약
 * - `remind`: 리마인더 관리 (추가/목록/완료/전체삭제)
 * - 기타: 일반 에이전트 질의
 *
 * @see SlackSlashIntentParser
 * @see SlackCommandHandler
 */
class DefaultSlackCommandHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: SlackMessagingService,
    private val defaultProvider: String = "configured backend model",
    private val threadTracker: SlackThreadTracker? = null,
    private val reminderStore: SlackReminderStore? = null,
    private val userEmailResolver: SlackUserEmailResolver? = null,
    private val mcpManager: McpManager? = null,
    private val userMemoryManager: UserMemoryManager? = null
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
                SlackSlashIntent.Help -> {
                    handleHelp(command)
                    return
                }

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

        val userContext = resolveUserContext(command.userId)
        val systemPrompt = buildString {
            append(SlackSystemPromptFactory.build(defaultProvider, buildToolSummary()))
            if (userContext.isNotBlank()) {
                append("\n\n")
                append(userContext)
            }
        }

        return agentExecutor.execute(
            AgentCommand(
                systemPrompt = systemPrompt,
                userPrompt = intent.prompt,
                userId = command.userId,
                metadata = metadata
            )
        )
    }

    private suspend fun resolveUserContext(userId: String): String {
        val manager = userMemoryManager ?: return ""
        return try {
            manager.getContextPrompt(userId)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to resolve user memory for userId=$userId" }
            ""
        }
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
        val timeInfo = if (reminder.dueAt != null) {
            " :bell: I'll DM you at <!date^${reminder.dueAt.epochSecond}^{time}|${reminder.dueAt}>."
        } else ""
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = "Saved reminder #${reminder.id}: ${reminder.text}$timeInfo"
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

    private suspend fun handleHelp(command: SlackSlashCommand) {
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = HELP_TEXT
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

    companion object {
        const val HELP_TEXT = """*Arc Reactor Commands* :robot_face:

*General*
`/jarvis <question>` — Ask anything (AI agent)
`/jarvis help` — Show this help message

*Daily Productivity*
`/jarvis brief [focus]` — Daily brief with 3 priorities + risk check
`/jarvis my-work [scope]` — Work status summary (In Progress / Waiting / Next)

*Reminders*
`/jarvis remind <text>` — Save a reminder (add `at HH:mm` for scheduled DM)
`/jarvis remind list` — List your reminders
`/jarvis remind done <id>` — Mark reminder as done
`/jarvis remind clear` — Clear all reminders

*Tips*
• @mention the bot in any channel for a threaded conversation
• React with :thumbsup: or :thumbsdown: on bot responses to give feedback"""
    }
}
