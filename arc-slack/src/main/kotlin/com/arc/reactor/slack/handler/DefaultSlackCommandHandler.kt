package com.arc.reactor.slack.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.scheduler.ScheduledJobStore
import com.arc.reactor.scheduler.ScheduledJob
import com.arc.reactor.scheduler.ScheduledJobType
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.slack.service.SlackUserEmailResolver
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import java.util.UUID

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
    private val userMemoryManager: UserMemoryManager? = null,
    private val scheduledJobStore: ScheduledJobStore? = null,
    private val dynamicSchedulerService: DynamicSchedulerService? = null
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
            dispatchIntent(command, rawPrompt)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "슬래시 명령 처리 실패: channel=${command.channelId}" }
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                text = ":x: An internal error occurred. Please try again later."
            )
        }
    }

    /** 파싱된 인텐트에 따라 적절한 핸들러로 분기한다. */
    private suspend fun dispatchIntent(command: SlackSlashCommand, rawPrompt: String) {
        when (val intent = SlackSlashIntentParser.parse(rawPrompt)) {
            SlackSlashIntent.Help -> handleHelp(command)
            is SlackSlashIntent.ReminderAdd -> handleReminderAdd(command, intent)
            SlackSlashIntent.ReminderList -> handleReminderList(command)
            is SlackSlashIntent.ReminderDone -> handleReminderDone(command, intent)
            SlackSlashIntent.ReminderClear -> handleReminderClear(command)
            is SlackSlashIntent.LoopCreate -> handleLoopCreate(command, intent)
            SlackSlashIntent.LoopList -> handleLoopList(command)
            is SlackSlashIntent.LoopStop -> handleLoopStop(command, intent)
            SlackSlashIntent.LoopClear -> handleLoopClear(command)
            is SlackSlashIntent.Agent -> handleAgentIntent(command, intent, rawPrompt)
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
            logger.info { "슬래시 명령 채널 게시 실패, response_url로 폴백: error=${result.error}" }
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
            logger.warn { "스레드 답장 실패, response_url로 폴백: error=${sendResult.error}" }
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
            logger.error { "response_url을 통한 슬래시 응답 전송 실패" }
        }
    }

    private suspend fun executeAgent(
        command: SlackSlashCommand,
        intent: SlackSlashIntent.Agent,
        sessionId: String
    ): com.arc.reactor.agent.model.AgentResult {
        val metadata = buildMetadata(command, intent, sessionId)
        val systemPrompt = buildSystemPrompt(command.userId)
        return agentExecutor.execute(
            AgentCommand(
                systemPrompt = systemPrompt,
                userPrompt = intent.prompt,
                userId = command.userId,
                metadata = metadata
            )
        )
    }

    /** 에이전트 실행에 필요한 메타데이터 맵을 구성한다. */
    private suspend fun buildMetadata(
        command: SlackSlashCommand,
        intent: SlackSlashIntent.Agent,
        sessionId: String
    ): MutableMap<String, Any> {
        val requesterEmail = SlackHandlerSupport.resolveRequesterEmail(command.userId, userEmailResolver)
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
        return metadata
    }

    /** 사용자 컨텍스트를 포함한 시스템 프롬프트를 구성한다. */
    private suspend fun buildSystemPrompt(userId: String): String {
        val userContext = SlackHandlerSupport.resolveUserContext(userId, userMemoryManager)
        return buildString {
            append(SlackSystemPromptFactory.build(defaultProvider, SlackHandlerSupport.buildToolSummary(mcpManager)))
            if (userContext.isNotBlank()) {
                append("\n\n")
                append(userContext)
            }
        }
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

    // ── Loop 핸들러 ──

    private suspend fun handleLoopCreate(command: SlackSlashCommand, intent: SlackSlashIntent.LoopCreate) {
        val store = scheduledJobStore ?: return sendLoopUnavailable(command)
        val scheduler = dynamicSchedulerService ?: return sendLoopUnavailable(command)

        if (!LoopIntervalParser.isValidMinInterval(intent.interval)) {
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                responseType = "ephemeral",
                text = ":x: 최소 간격은 30분입니다."
            )
            return
        }
        val cronExpression = LoopIntervalParser.toCron(intent.interval)
        if (cronExpression == null) {
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                responseType = "ephemeral",
                text = ":x: 인터벌 형식을 인식할 수 없습니다. 예: `30m`, `9am`, `daily`, `weekly`"
            )
            return
        }

        val userJobs = getUserLoopJobs(store, command.userId)
        if (userJobs.size >= MAX_LOOPS_PER_USER) {
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                responseType = "ephemeral",
                text = ":x: 최대 ${MAX_LOOPS_PER_USER}개까지 등록할 수 있습니다. `/jarvis loop list`로 확인 후 삭제해 주세요."
            )
            return
        }

        val job = ScheduledJob(
            id = UUID.randomUUID().toString(),
            name = "user-loop-${command.userId}-${System.currentTimeMillis()}",
            description = intent.prompt,
            cronExpression = cronExpression,
            jobType = ScheduledJobType.AGENT,
            agentPrompt = intent.prompt,
            slackChannelId = command.userId,
            tags = setOf(USER_LOOP_TAG, command.userId),
            enabled = true
        )
        scheduler.create(job)

        val desc = LoopIntervalParser.toDescription(intent.interval)
        val count = userJobs.size + 1
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = ":white_check_mark: 스케줄 등록 완료 ($count/$MAX_LOOPS_PER_USER)\n" +
                ":alarm_clock: $desc\n" +
                ":memo: ${intent.prompt}\n" +
                ":postbox: 전달: DM"
        )
    }

    private suspend fun handleLoopList(command: SlackSlashCommand) {
        val store = scheduledJobStore ?: return sendLoopUnavailable(command)
        val jobs = getUserLoopJobs(store, command.userId)
        if (jobs.isEmpty()) {
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                responseType = "ephemeral",
                text = "등록된 스케줄이 없습니다. 예: `/jarvis loop 9am 내 이슈 요약해줘`"
            )
            return
        }
        val list = jobs.mapIndexed { idx, job ->
            val status = if (job.enabled) ":alarm_clock:" else ":no_entry_sign:"
            "$status ${idx + 1}. ${job.description ?: job.agentPrompt ?: "?"}"
        }.joinToString("\n")
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = ":clipboard: 내 스케줄 (${jobs.size}/$MAX_LOOPS_PER_USER)\n$list"
        )
    }

    private suspend fun handleLoopStop(command: SlackSlashCommand, intent: SlackSlashIntent.LoopStop) {
        val store = scheduledJobStore ?: return sendLoopUnavailable(command)
        val scheduler = dynamicSchedulerService
        val jobs = getUserLoopJobs(store, command.userId)
        val idx = intent.id - 1
        if (idx !in jobs.indices) {
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                responseType = "ephemeral",
                text = ":x: ${intent.id}번 스케줄을 찾을 수 없습니다. `/jarvis loop list`로 확인해 주세요."
            )
            return
        }
        val job = jobs[idx]
        scheduler?.delete(job.id) ?: store.delete(job.id)
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = ":white_check_mark: ${intent.id}번 스케줄 삭제 완료 (${jobs.size - 1}/$MAX_LOOPS_PER_USER)"
        )
    }

    private suspend fun handleLoopClear(command: SlackSlashCommand) {
        val store = scheduledJobStore ?: return sendLoopUnavailable(command)
        val scheduler = dynamicSchedulerService
        val jobs = getUserLoopJobs(store, command.userId)
        for (job in jobs) {
            scheduler?.delete(job.id) ?: store.delete(job.id)
        }
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = ":white_check_mark: ${jobs.size}개 스케줄 전체 삭제 완료."
        )
    }

    private fun getUserLoopJobs(store: ScheduledJobStore, userId: String): List<ScheduledJob> {
        return store.list().filter { job ->
            job.tags.contains(USER_LOOP_TAG) && job.tags.contains(userId)
        }
    }

    private suspend fun sendLoopUnavailable(command: SlackSlashCommand) {
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = "스케줄 기능을 사용할 수 없습니다. 시스템 관리자에게 문의해 주세요."
        )
    }

    // ── Reminder 핸들러 ──

    private suspend fun sendReminderUnavailable(command: SlackSlashCommand) {
        messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            responseType = "ephemeral",
            text = "Reminder feature is temporarily unavailable. Please try again later."
        )
    }

    companion object {
        private const val USER_LOOP_TAG = "user-loop"
        private const val MAX_LOOPS_PER_USER = 5

        const val HELP_TEXT = """*Reactor 명령어* :robot_face:

*일반*
`/jarvis <질문>` — AI 에이전트에게 질문
`/jarvis help` — 도움말

*업무 브리핑*
`/jarvis brief [주제]` — 일일 브리핑 (3개 우선순위 + 리스크 체크)
`/jarvis my-work [범위]` — 업무 현황 요약 (진행 중 / 대기 / 다음)

*주기적 스케줄 (Loop)*
`/jarvis loop <간격> <내용>` — 주기적 브리핑 등록 (최대 ${MAX_LOOPS_PER_USER}개)
`/jarvis loop list` — 내 스케줄 목록
`/jarvis loop stop <번호>` — 스케줄 삭제
`/jarvis loop clear` — 전체 삭제
_간격 예시: 30m, 1h, 9am, 14:30, daily, weekly, weekday_

*리마인더*
`/jarvis remind <내용>` — 리마인더 저장 (`at HH:mm` 추가 시 DM 알림)
`/jarvis remind list` — 목록 보기
`/jarvis remind done <번호>` — 완료 처리
`/jarvis remind clear` — 전체 삭제

*팁*
• 채널에서 @봇 멘션으로 스레드 대화 가능
• 봇 응답에 :thumbsup: :thumbsdown: 반응으로 피드백"""
    }
}
