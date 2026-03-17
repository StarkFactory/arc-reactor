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
 * 기본 Slack 이벤트 핸들러. [AgentExecutor]에 위임하여 에이전트를 실행한다.
 *
 * - @mention 이벤트에서 봇 멘션 태그를 제거하여 깨끗한 텍스트 추출
 * - Slack 스레드를 arc-reactor 세션(sessionId)에 매핑
 * - 에이전트 응답을 Slack 스레드에 전송
 * - Guard 파이프라인은 AgentExecutor.execute()를 통해 자동 적용
 * - 교차 도구 연계: 연결된 MCP 도구 요약을 시스템 프롬프트에 주입
 * - 선행적(proactive) 모드: 채널 메시지를 처리하되 [NO_RESPONSE] 필터링 적용
 * - 리액션 피드백: 봇 응답에 대한 이모지 리액션을 [FeedbackStore]에 저장
 *
 * @see SlackEventHandler
 * @see SlackSystemPromptFactory
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
            val toolSummary = SlackHandlerSupport.buildToolSummary(mcpManager)
            val userContext = SlackHandlerSupport.resolveUserContext(command.userId, userMemoryManager)
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
            val toolSummary = SlackHandlerSupport.buildToolSummary(mcpManager)
            val userContext = SlackHandlerSupport.resolveUserContext(userId, userMemoryManager)

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
        val requesterEmail = SlackHandlerSupport.resolveRequesterEmail(userId, userEmailResolver)
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
