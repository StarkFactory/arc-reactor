package com.arc.reactor.slack.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.feedback.Feedback
import com.arc.reactor.feedback.FeedbackRating
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.session.SlackBotResponseTracker
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.slack.service.SlackUserEmailResolver
import com.arc.reactor.slack.service.SlackUserNameResolver
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
    private val userNameResolver: SlackUserNameResolver? = null,
    private val mcpManager: McpManager? = null,
    private val feedbackStore: FeedbackStore? = null,
    private val botResponseTracker: SlackBotResponseTracker? = null,
    private val userMemoryManager: UserMemoryManager? = null,
    private val personaStore: PersonaStore? = null
) : SlackEventHandler {

    override suspend fun handleAppMention(command: SlackEventCommand) {
        val cleanText = command.text.replace(MENTION_REGEX, "").trim()
        if (cleanText.isBlank()) {
            logger.debug { "빈 멘션 무시: user=${command.userId}" }
            return
        }

        val threadTs = command.threadTs ?: command.ts

        // R176: 멘션 자체가 quit 명령이면 (예: @reactor 나가) 작별 후 종료
        if (isQuitCommand(cleanText)) {
            handleQuitCommand(command.channelId, threadTs, command.userId)
            return
        }

        threadTracker?.track(command.channelId, threadTs)
        executeAndRespond(command.channelId, threadTs, command.userId, cleanText)
    }

    override suspend fun handleMessage(command: SlackEventCommand) {
        val text = command.text.replace(MENTION_REGEX, "").trim()
        if (text.isBlank()) return

        val threadTs = command.threadTs ?: command.ts

        // R176: "나가" 류 종료 명령 감지 → 작별 인사 후 스레드 추적 해제 (낄끼빠빠)
        if (isQuitCommand(text)) {
            handleQuitCommand(command.channelId, threadTs, command.userId)
            return
        }

        threadTracker?.track(command.channelId, threadTs)
        executeAndRespond(command.channelId, threadTs, command.userId, text)
    }

    /**
     * 사용자가 봇을 스레드에서 내보내려는 명령인지 판단한다.
     * 짧고 명확한 패턴만 매칭 — 일반 대화에서 우연히 매칭되지 않도록 길이 제한.
     */
    private fun isQuitCommand(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.length > 30) return false
        return QUIT_COMMAND_PATTERNS.any { normalized.contains(it) }
    }

    /**
     * 종료 명령에 대한 작별 인사를 보내고 스레드 추적을 해제한다.
     * 이후 동일 스레드의 메시지는 봇이 무시한다 (다시 멘션하면 자동 재추적).
     */
    private suspend fun handleQuitCommand(
        channelId: String,
        threadTs: String,
        userId: String
    ) {
        val displayName = userNameResolver?.resolveName(userId)
            ?.takeIf { it.isNotBlank() && !SLACK_USER_ID_REGEX.matches(it) }
        val mention = "<@$userId>"
        val farewell = if (displayName != null) {
            "$mention 알겠습니다, $displayName 님! 잠시 자리 비킬게요. 필요하면 `@reactor`로 다시 불러주세요. \uD83D\uDC4B"
        } else {
            "$mention 알겠습니다! 잠시 자리 비킬게요. 필요하면 `@reactor`로 다시 불러주세요. \uD83D\uDC4B"
        }
        try {
            messagingService.sendMessage(channelId = channelId, text = farewell, threadTs = threadTs)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "작별 메시지 전송 실패: channel=$channelId" }
        }
        threadTracker?.untrack(channelId, threadTs)
        logger.info { "스레드 추적 해제 (quit 명령): channel=$channelId thread=$threadTs by user=$userId" }
    }

    override suspend fun handleChannelMessage(command: SlackEventCommand): Boolean {
        val text = command.text.trim()
        if (text.isBlank()) return false
        try {
            val result = executeProactiveAgent(command, text)
            val content = result.content?.trim().orEmpty()
            if (!result.success || content == NO_RESPONSE_MARKER || content.isBlank()) return false
            return sendProactiveResponse(command.channelId, command.ts, content)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "선행적 처리 실패: channel=${command.channelId}" }
            return false
        }
    }

    /** 선행적 모드용 에이전트를 실행한다. */
    private suspend fun executeProactiveAgent(command: SlackEventCommand, text: String): AgentResult {
        val toolSummary = SlackHandlerSupport.buildToolSummary(mcpManager)
        val userContext = SlackHandlerSupport.resolveUserContext(command.userId, userMemoryManager)
        val basePrompt = SlackSystemPromptFactory.buildProactive(defaultProvider, toolSummary)
        val systemPrompt = if (userContext.isNotBlank()) "$basePrompt\n\n$userContext" else basePrompt
        val sessionId = "slack-proactive-${command.channelId}-${command.ts}"
        val metadata = buildMetadata(sessionId, command.channelId, command.userId)
        metadata["entrypoint"] = "proactive"
        return agentExecutor.execute(
            AgentCommand(systemPrompt = systemPrompt, userPrompt = text, userId = command.userId, metadata = metadata)
        )
    }

    /** 선행적 응답을 전송하고 스레드를 추적한다. */
    private suspend fun sendProactiveResponse(channelId: String, threadTs: String, content: String): Boolean {
        val sendResult = messagingService.sendMessage(channelId = channelId, text = content, threadTs = threadTs)
        if (sendResult.ok) threadTracker?.track(channelId, threadTs)
        return sendResult.ok
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
            logger.info { "피드백 기록 완료: user=$userId rating=$rating session=$sessionId" }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "리액션 피드백 저장 실패: user=$userId session=$sessionId" }
        }
    }

    private suspend fun executeAndRespond(
        channelId: String,
        threadTs: String,
        userId: String,
        userPrompt: String
    ) {
        // DM(D로 시작)에서 스레드 없이 대화 시 채널 기반 세션으로 맥락 유지
        val sessionKey = if (channelId.startsWith("D")) "dm" else threadTs
        val sessionId = "slack-$channelId-$sessionKey"
        try {
            setAssistantStatusSafely(channelId, threadTs, "생각하고 있어요...")
            val result = executeAgent(sessionId, channelId, userId, userPrompt)
            setAssistantStatusSafely(channelId, threadTs, "")
            val content = result.content.orEmpty().trim()
            if (content == NO_RESPONSE_MARKER) return
            // R176: 응답 대상 명시 — 다수 채널에서 누구한테 답하는지 명확하게
            sendAgentResponse(channelId, threadTs, result, sessionId, userPrompt, userId)
        } catch (e: Exception) {
            e.throwIfCancellation()
            setAssistantStatusSafely(channelId, threadTs, "")
            logger.error(e) { "Slack 이벤트 처리 실패: channel=$channelId" }
            sendErrorFallback(channelId, threadTs)
        }
    }

    /** 사용자 이름을 해석하고 에이전트를 실행한다. */
    private suspend fun executeAgent(
        sessionId: String,
        channelId: String,
        userId: String,
        userPrompt: String
    ): AgentResult {
        val displayName = userNameResolver?.resolveName(userId)
        // Slack User ID 형식(U로 시작하는 대문자+숫자)이면 prefix 생략 — raw ID 노출 방지
        val prefixed = if (displayName != null && !SLACK_USER_ID_REGEX.matches(displayName)) {
            "[$displayName] $userPrompt"
        } else {
            userPrompt
        }
        val command = buildAgentCommand(sessionId, channelId, userId, prefixed)
        return agentExecutor.execute(command)
    }

    /**
     * Agents & AI Apps 스레드 상태를 설정한다.
     * 비DM 채널이나 API 실패 시 조용히 무시한다.
     */
    private suspend fun setAssistantStatusSafely(
        channelId: String,
        threadTs: String,
        status: String
    ) {
        try {
            messagingService.setAssistantThreadStatus(channelId, threadTs, status)
        } catch (e: Exception) {
            e.throwIfCancellation()
            // Agents & AI Apps가 아닌 일반 채널이면 실패 정상 — 무시
        }
    }

    /** 세션·메타데이터·시스템 프롬프트를 조합하여 [AgentCommand]를 생성한다. */
    private suspend fun buildAgentCommand(
        sessionId: String,
        channelId: String,
        userId: String,
        userPrompt: String
    ): AgentCommand {
        val metadata = buildMetadata(sessionId, channelId, userId)
        val toolSummary = SlackHandlerSupport.buildToolSummary(mcpManager)
        val userContext = SlackHandlerSupport.resolveUserContext(userId, userMemoryManager)

        val personaPrompt = personaStore?.getDefault()?.systemPrompt
        val systemPrompt = buildString {
            append(SlackSystemPromptFactory.build(personaPrompt, defaultProvider, toolSummary))
            if (userContext.isNotBlank()) {
                append("\n\n")
                append(userContext)
            }
        }

        return AgentCommand(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            userId = userId,
            metadata = metadata
        )
    }

    /**
     * 에이전트 응답을 Slack 스레드에 전송하고 봇 응답을 추적한다.
     *
     * R176: 응답 맨 앞에 사용자 mention을 추가하여 다수가 있는 채널에서
     * "이건 X한테 답한 거예요"를 명확히 한다. 응답 텍스트에 이미 mention이
     * 포함되어 있으면 중복 추가하지 않는다.
     */
    private suspend fun sendAgentResponse(
        channelId: String,
        threadTs: String,
        result: AgentResult,
        sessionId: String,
        userPrompt: String,
        targetUserId: String
    ) {
        val rawText = SlackResponseTextFormatter.fromResult(result, userPrompt)
        val responseText = prependTargetMentionIfMissing(rawText, targetUserId)
        val blocks = SlackBlockKitFormatter.buildBlocks(result, userPrompt)
        val sendResult = messagingService.sendMessage(
            channelId = channelId,
            text = responseText,
            threadTs = threadTs,
            blocks = blocks
        )
        if (sendResult.ok && sendResult.ts != null) {
            botResponseTracker?.track(channelId, sendResult.ts, sessionId, userPrompt)
        }
        if (!sendResult.ok) {
            logger.warn {
                "Slack 이벤트 응답 전송 실패: " +
                    "channel=$channelId thread=$threadTs error=${sendResult.error}"
            }
        }
    }

    /**
     * 응답 텍스트 맨 앞에 대상 사용자 mention을 추가한다.
     * 이미 응답에 동일 사용자 mention이 포함되어 있으면 중복 추가하지 않는다.
     * R176: 다수 채널에서 누구에게 답하는 건지 명확히 하기 위함.
     */
    private fun prependTargetMentionIfMissing(text: String, targetUserId: String): String {
        if (targetUserId.isBlank()) return text
        val mention = "<@$targetUserId>"
        if (text.contains(mention)) return text
        return "$mention $text"
    }

    /** 에이전트 실행 실패 시 사용자에게 오류 메시지를 전송한다. */
    private suspend fun sendErrorFallback(channelId: String, threadTs: String) {
        try {
            messagingService.sendMessage(
                channelId = channelId,
                text = ":x: 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
                threadTs = threadTs
            )
        } catch (sendError: Exception) {
            sendError.throwIfCancellation()
            logger.error(sendError) { "Slack 오류 메시지 전송 실패" }
        }
    }

    private suspend fun buildMetadata(
        sessionId: String,
        channelId: String,
        userId: String
    ): MutableMap<String, Any> {
        val metadata = mutableMapOf<String, Any>(
            "sessionId" to sessionId,
            "source" to "slack",
            "channel" to "slack",
            "channelId" to channelId
        )
        // 전체 identity 조회 시도 → 실패 시 이메일만 폴백
        val identity = SlackHandlerSupport.resolveIdentity(userId, userEmailResolver)
        if (identity != null) {
            metadata["requesterEmail"] = identity.email
            metadata["slackUserEmail"] = identity.email
            metadata["userEmail"] = identity.email
            val jiraAccountId = identity.jiraAccountId
            if (!jiraAccountId.isNullOrBlank()) {
                metadata["requesterAccountId"] = jiraAccountId
            }
            val displayName = identity.displayName
            if (!displayName.isNullOrBlank()) {
                metadata["requesterDisplayName"] = displayName
            }
        } else {
            val requesterEmail = SlackHandlerSupport.resolveRequesterEmail(userId, userEmailResolver)
            if (!requesterEmail.isNullOrBlank()) {
                metadata["requesterEmail"] = requesterEmail
                metadata["slackUserEmail"] = requesterEmail
                metadata["userEmail"] = requesterEmail
            }
        }
        return metadata
    }

    companion object {
        private val MENTION_REGEX = Regex("<@[A-Za-z0-9]+>")
        /** Slack User ID 형식 (예: U088X6MECJD). resolveName 폴백 시 raw ID 노출 방지용. */
        private val SLACK_USER_ID_REGEX = Regex("^U[A-Z0-9]+$")
        private const val NO_RESPONSE_MARKER = "[NO_RESPONSE]"

        /**
         * 봇을 스레드에서 내보내는 종료 명령 패턴 (R176).
         * 짧고 명확한 표현만 매칭 — 일반 대화에서 우연히 트리거되지 않도록 isQuitCommand에서 길이 30자 제한.
         * 한국어/영어 둘 다 지원.
         */
        private val QUIT_COMMAND_PATTERNS = listOf(
            "나가", "꺼져", "그만", "물러가", "비켜", "닥쳐", "조용",
            "엑터야 나가", "리액터 나가", "reactor 나가",
            "go away", "shut up", "be quiet", "leave us", "stop", "dismiss"
        )
        val REACTION_TO_RATING = mapOf(
            "+1" to FeedbackRating.THUMBS_UP,
            "thumbsup" to FeedbackRating.THUMBS_UP,
            "-1" to FeedbackRating.THUMBS_DOWN,
            "thumbsdown" to FeedbackRating.THUMBS_DOWN
        )
    }
}
