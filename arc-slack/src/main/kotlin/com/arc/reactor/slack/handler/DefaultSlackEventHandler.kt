package com.arc.reactor.slack.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.feedback.Feedback
import com.arc.reactor.feedback.FeedbackRating
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.session.SlackBotResponseTracker
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.slack.service.SlackUserEmailResolver
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default Slack event handler that delegates to AgentExecutor.
 *
 * - Extracts clean text from @mention events (removes bot mention tag)
 * - Maps Slack threads to arc-reactor sessions via sessionId
 * - Sends agent response back to the Slack thread
 * - Guard pipeline is applied automatically via AgentExecutor.execute()
 * - Cross-tool correlation: injects connected MCP tool summaries into system prompt
 * - Proactive mode: handles channel messages with [NO_RESPONSE] filtering
 */
class DefaultSlackEventHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: SlackMessagingService,
    private val defaultProvider: String = "configured backend model",
    private val threadTracker: SlackThreadTracker? = null,
    private val userEmailResolver: SlackUserEmailResolver? = null,
    private val mcpManager: McpManager? = null,
    private val feedbackStore: FeedbackStore? = null,
    private val botResponseTracker: SlackBotResponseTracker? = null,
    private val userMemoryManager: UserMemoryManager? = null
) : SlackEventHandler {

    override suspend fun handleAppMention(command: SlackEventCommand) {
        val cleanText = command.text.replace(MENTION_REGEX, "").trim()
        if (cleanText.isBlank()) {
            logger.debug { "Empty mention from user=${command.userId}, skipping" }
            return
        }

        val threadTs = command.threadTs ?: command.ts
        threadTracker?.track(command.channelId, threadTs)
        executeAndRespond(command.channelId, threadTs, command.userId, cleanText)
    }

    override suspend fun handleMessage(command: SlackEventCommand) {
        val text = command.text.trim()
        if (text.isBlank()) return

        val threadTs = command.threadTs ?: command.ts
        threadTracker?.track(command.channelId, threadTs)
        executeAndRespond(command.channelId, threadTs, command.userId, text)
    }

    override suspend fun handleChannelMessage(command: SlackEventCommand): Boolean {
        val text = command.text.trim()
        if (text.isBlank()) return false

        val threadTs = command.ts
        try {
            val toolSummary = buildToolSummary()
            val userContext = resolveUserContext(command.userId)
            val basePrompt = SlackSystemPromptFactory.buildProactive(
                defaultProvider, toolSummary
            )
            val systemPrompt = if (userContext.isNotBlank()) "$basePrompt\n\n$userContext" else basePrompt
            val sessionId = "slack-proactive-${command.channelId}-$threadTs"
            val metadata = buildMetadata(sessionId, command.channelId, command.userId)
            metadata["entrypoint"] = "proactive"

            val result = agentExecutor.execute(
                AgentCommand(
                    systemPrompt = systemPrompt,
                    userPrompt = text,
                    userId = command.userId,
                    metadata = metadata
                )
            )

            val content = result.content?.trim().orEmpty()
            if (!result.success || content == NO_RESPONSE_MARKER || content.isBlank()) {
                logger.debug { "Proactive agent declined for channel=${command.channelId}" }
                return false
            }

            val sendResult = messagingService.sendMessage(
                channelId = command.channelId,
                text = content,
                threadTs = threadTs
            )
            if (sendResult.ok) {
                threadTracker?.track(command.channelId, threadTs)
            }
            return sendResult.ok
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Proactive handling failed for channel=${command.channelId}" }
            return false
        }
    }

    override suspend fun handleReaction(
        userId: String,
        channelId: String,
        messageTs: String,
        reaction: String,
        sessionId: String,
        userPrompt: String
    ) {
        val store = feedbackStore ?: return
        val rating = REACTION_TO_RATING[reaction] ?: return
        try {
            store.save(
                Feedback(
                    query = userPrompt,
                    response = "",
                    rating = rating,
                    sessionId = sessionId,
                    userId = userId
                )
            )
            logger.info { "Feedback recorded: user=$userId rating=$rating session=$sessionId" }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to save reaction feedback: user=$userId session=$sessionId" }
        }
    }

    private suspend fun executeAndRespond(
        channelId: String,
        threadTs: String,
        userId: String,
        userPrompt: String
    ) {
        try {
            val sessionId = "slack-$channelId-$threadTs"
            val metadata = buildMetadata(sessionId, channelId, userId)
            val toolSummary = buildToolSummary()
            val userContext = resolveUserContext(userId)

            val systemPrompt = buildString {
                append(SlackSystemPromptFactory.build(defaultProvider, toolSummary))
                if (userContext.isNotBlank()) {
                    append("\n\n")
                    append(userContext)
                }
            }

            val result = agentExecutor.execute(
                AgentCommand(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    userId = userId,
                    metadata = metadata
                )
            )

            val responseText = SlackResponseTextFormatter.fromResult(result, userPrompt)
            val sendResult = messagingService.sendMessage(
                channelId = channelId,
                text = responseText,
                threadTs = threadTs
            )
            if (sendResult.ok && sendResult.ts != null) {
                botResponseTracker?.track(channelId, sendResult.ts, sessionId, userPrompt)
            }
            if (!sendResult.ok) {
                logger.warn {
                    "Failed to send Slack event response: " +
                        "channel=$channelId thread=$threadTs error=${sendResult.error}"
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Failed to process Slack event for channel=$channelId, thread=$threadTs" }
            try {
                messagingService.sendMessage(
                    channelId = channelId,
                    text = ":x: An internal error occurred. Please try again later.",
                    threadTs = threadTs
                )
            } catch (sendError: Exception) {
                sendError.throwIfCancellation()
                logger.error(sendError) { "Failed to send error message to Slack" }
            }
        }
    }

    private suspend fun buildMetadata(
        sessionId: String,
        channelId: String,
        userId: String
    ): MutableMap<String, Any> {
        val requesterEmail = resolveRequesterEmail(userId)
        val metadata = mutableMapOf<String, Any>(
            "sessionId" to sessionId,
            "source" to "slack",
            "channel" to "slack",
            "channelId" to channelId
        )
        if (!requesterEmail.isNullOrBlank()) {
            metadata["requesterEmail"] = requesterEmail
            metadata["slackUserEmail"] = requesterEmail
            metadata["userEmail"] = requesterEmail
        }
        return metadata
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

    companion object {
        private val MENTION_REGEX = Regex("<@[A-Za-z0-9]+>")
        private const val NO_RESPONSE_MARKER = "[NO_RESPONSE]"
        val REACTION_TO_RATING = mapOf(
            "+1" to FeedbackRating.THUMBS_UP,
            "thumbsup" to FeedbackRating.THUMBS_UP,
            "-1" to FeedbackRating.THUMBS_DOWN,
            "thumbsdown" to FeedbackRating.THUMBS_DOWN
        )
    }
}
