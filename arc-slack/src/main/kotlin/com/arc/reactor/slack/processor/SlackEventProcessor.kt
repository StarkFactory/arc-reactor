package com.arc.reactor.slack.processor

import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.controller.SlackEventDeduplicator
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.proactive.ProactiveChannelStore
import com.arc.reactor.slack.resilience.SlackUserRateLimiter
import com.arc.reactor.slack.session.SlackBotResponseTracker
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.springframework.beans.factory.DisposableBean
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Slack 이벤트 비동기 처리기.
 *
 * Events API 또는 Socket Mode에서 수신된 이벤트 콜백을 처리한다.
 *
 * 주요 기능:
 * - `app_mention`, `message` 이벤트 디스패치
 * - `reaction_added` 이벤트를 피드백 수집으로 라우팅
 * - backpressure (동시성 제한 + 타임아웃 큐)
 * - 이벤트 ID 기반 중복 방지
 * - 선행적(proactive) 채널 모니터링
 *
 * @param eventHandler 실제 이벤트 처리 핸들러
 * @param messagingService 메시지 전송 서비스 (드롭 알림용)
 * @param metricsRecorder 메트릭 기록기
 * @param properties Slack 설정 (동시성, 타임아웃 등)
 * @param threadTracker 스레드 추적기 (선택, 미추적 스레드 메시지 무시용)
 * @param proactiveChannelStore 선행적 모니터링 대상 채널 저장소 (선택)
 * @param botResponseTracker 봇 응답 추적기 (선택, 리액션 피드백용)
 * @see SlackEventHandler
 * @see SlackBackpressureLimiter
 */
class SlackEventProcessor(
    private val eventHandler: SlackEventHandler,
    private val messagingService: SlackMessagingService,
    private val metricsRecorder: SlackMetricsRecorder,
    properties: SlackProperties,
    private val threadTracker: SlackThreadTracker? = null,
    private val proactiveChannelStore: ProactiveChannelStore? = null,
    private val botResponseTracker: SlackBotResponseTracker? = null,
    private val userRateLimiter: SlackUserRateLimiter? = null,
    scope: CoroutineScope? = null
) : DisposableBean {
    private val scope = scope ?: CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val backpressureLimiter = SlackBackpressureLimiter(
        maxConcurrentRequests = properties.maxConcurrentRequests,
        requestTimeoutMs = properties.requestTimeoutMs,
        failFastOnSaturation = properties.failFastOnSaturation
    )
    private val notifyOnDrop = properties.notifyOnDrop
    private val processDirectMessagesWithoutThread = properties.processDirectMessagesWithoutThread
    private val proactiveEnabled = properties.proactiveEnabled
    private val proactiveSemaphore = Semaphore(properties.proactiveMaxConcurrent.coerceAtLeast(1))
    private val deduplicator = SlackEventDeduplicator(
        enabled = properties.eventDedupEnabled,
        ttlSeconds = properties.eventDedupTtlSeconds,
        maxEntries = properties.eventDedupMaxEntries
    )

    fun submitEventCallback(
        payload: JsonNode,
        entrypoint: String,
        retryNum: String? = null,
        retryReason: String? = null
    ) {
        val event = payload.path("event")
        val eventType = event.path("type").asText()
        metricsRecorder.recordInbound(entrypoint = entrypoint, eventType = eventType)
        val eventId = payload.path("event_id").asText().takeIf { it.isNotBlank() }

        if (retryNum != null || retryReason != null) {
            logger.info { "Slack 재시도 콜백 수신: eventId=$eventId, retryNum=$retryNum, reason=$retryReason" }
        }

        if (eventId != null && deduplicator.isDuplicateAndMark(eventId)) {
            logger.info { "중복 Slack 이벤트 무시: eventId=$eventId, type=$eventType" }
            metricsRecorder.recordDuplicate(eventType)
            return
        }

        processEventAsync(event, eventType, entrypoint)
    }

    /**
     * 이벤트 비동기 처리 오케스트레이터.
     *
     * 검증 → 백프레셔 획득 → 이벤트 디스패치 순서로 실행한다.
     */
    private fun processEventAsync(event: JsonNode, eventType: String, entrypoint: String) {
        if (eventType == "reaction_added") {
            handleReactionEvent(event, entrypoint)
            return
        }
        // Agents & AI Apps 이벤트 처리
        if (eventType == "assistant_thread_started") {
            handleAssistantThreadStarted(event, entrypoint)
            return
        }
        val command = validateAndFilterEvent(event, eventType, entrypoint) ?: return

        if (!acquireProcessingPermitOrReject(command, eventType, entrypoint)) return

        scope.launch {
            if (!acquireQueuedPermitOrDrop(command, eventType, entrypoint)) return@launch
            executeWithMetrics(command, eventType, entrypoint)
        }
    }

    /**
     * 이벤트 검증 + 중복 필터링 + 사용자 레이트 리밋.
     *
     * 봇 메시지, subtype, 필수 필드 누락, 레이트 리밋 초과 시 null 반환.
     */
    private fun validateAndFilterEvent(
        event: JsonNode,
        eventType: String,
        entrypoint: String
    ): SlackEventCommand? {
        if (event.path("bot_id").asText().isNotEmpty()) return null
        if (event.path("subtype").asText().isNotEmpty()) return null

        val command = parseEventCommand(event, eventType)

        if (command.userId.isBlank() || command.channelId.isBlank()) {
            logger.debug { "사용자 또는 채널 누락으로 이벤트 무시" }
            return null
        }
        if (userRateLimiter != null && !userRateLimiter.tryAcquire(command.userId)) {
            metricsRecorder.recordDropped(entrypoint = entrypoint, reason = "user_rate_limited", eventType = eventType)
            scope.launch { notifyRateLimited(command) }
            return null
        }
        return command
    }

    /** JSON 이벤트 노드에서 SlackEventCommand를 파싱한다. */
    private fun parseEventCommand(event: JsonNode, eventType: String) = SlackEventCommand(
        eventType = eventType,
        userId = event.path("user").asText(),
        channelId = event.path("channel").asText(),
        text = event.path("text").asText(),
        ts = event.path("ts").asText(),
        threadTs = event.path("thread_ts").asText().takeIf { it.isNotEmpty() },
        channelType = event.path("channel_type").asText().takeIf { it.isNotBlank() }
    )

    /** fail-fast 백프레셔: 세마포어 포화 시 즉시 거부. 통과 시 true. */
    private fun acquireProcessingPermitOrReject(
        command: SlackEventCommand,
        eventType: String,
        entrypoint: String
    ): Boolean {
        if (!backpressureLimiter.rejectImmediatelyIfConfigured()) return true

        logger.warn {
            "이벤트 포화로 거부: " +
                "entrypoint=$entrypoint, type=$eventType, channel=${command.channelId}"
        }
        metricsRecorder.recordDropped(entrypoint = entrypoint, reason = "queue_overflow", eventType = eventType)
        if (notifyOnDrop) {
            scope.launch { notifyBusyIfInteractive(command) }
        }
        return false
    }

    /** 큐 모드 백프레셔: 타임아웃까지 대기 후 실패 시 false. */
    private suspend fun acquireQueuedPermitOrDrop(
        command: SlackEventCommand,
        eventType: String,
        entrypoint: String
    ): Boolean {
        if (backpressureLimiter.acquireForQueuedMode()) return true

        logger.warn {
            "이벤트 큐 타임아웃 드롭: " +
                "entrypoint=$entrypoint, type=$eventType, channel=${command.channelId}"
        }
        metricsRecorder.recordDropped(entrypoint = entrypoint, reason = "queue_timeout", eventType = eventType)
        if (notifyOnDrop) {
            notifyBusyIfInteractive(command)
        }
        return false
    }

    /** 이벤트 타입별 핸들러 디스패치 + 메트릭 기록. */
    private suspend fun executeWithMetrics(
        command: SlackEventCommand,
        eventType: String,
        entrypoint: String
    ) {
        val started = System.currentTimeMillis()
        try {
            dispatchEventByType(command, eventType, entrypoint)
            metricsRecorder.recordHandler(
                entrypoint = entrypoint, eventType = eventType,
                success = true, durationMs = System.currentTimeMillis() - started
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) {
                "Slack 이벤트 처리 실패: " +
                    "entrypoint=$entrypoint, type=$eventType, channel=${command.channelId}"
            }
            metricsRecorder.recordHandler(
                entrypoint = entrypoint, eventType = eventType,
                success = false, durationMs = System.currentTimeMillis() - started
            )
        } finally {
            backpressureLimiter.release()
        }
    }

    /** 이벤트 타입별 분기: app_mention, message(스레드/DM/proactive). */
    private suspend fun dispatchEventByType(
        command: SlackEventCommand,
        eventType: String,
        entrypoint: String
    ) {
        when (eventType) {
            "app_mention" -> eventHandler.handleAppMention(command)
            "message" -> dispatchMessageEvent(command, entrypoint)
        }
    }

    /** message 이벤트 세부 분기: 스레드 추적, DM, 선행적 모니터링. */
    private suspend fun dispatchMessageEvent(command: SlackEventCommand, entrypoint: String) {
        if (command.threadTs != null) {
            if (threadTracker != null && !threadTracker.isTracked(command.channelId, command.threadTs)) {
                logger.debug {
                    "미추적 스레드 메시지 무시: " +
                        "entrypoint=$entrypoint, channel=${command.channelId}, thread=${command.threadTs}"
                }
                metricsRecorder.recordDropped(
                    entrypoint = entrypoint, reason = "untracked_thread", eventType = "message"
                )
                return
            }
            eventHandler.handleMessage(command)
        } else if (processDirectMessagesWithoutThread && command.isDirectMessageChannel()) {
            eventHandler.handleMessage(command)
        } else if (isProactiveCandidate(command)) {
            handleProactive(command, entrypoint)
        }
    }

    private suspend fun notifyRateLimited(command: SlackEventCommand) {
        val threadTs = command.threadTs ?: command.ts
        if (threadTs.isBlank()) return

        try {
            messagingService.sendMessage(
                channelId = command.channelId,
                text = RATE_LIMITED_RESPONSE_TEXT,
                threadTs = threadTs
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "레이트 리밋 메시지 전송 실패: channel=${command.channelId}" }
        }
    }

    private suspend fun notifyBusyIfInteractive(command: SlackEventCommand) {
        val threadTs = command.threadTs ?: command.ts
        if (threadTs.isBlank()) return

        try {
            messagingService.sendMessage(
                channelId = command.channelId,
                text = BUSY_RESPONSE_TEXT,
                threadTs = threadTs
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "큐 타임아웃 메시지 전송 실패: channel=${command.channelId}" }
        }
    }

    private fun isProactiveCandidate(command: SlackEventCommand): Boolean {
        if (!proactiveEnabled) return false
        if (proactiveChannelStore == null || !proactiveChannelStore.isEnabled(command.channelId)) return false
        if (command.isDirectMessageChannel()) return false
        return true
    }

    private suspend fun handleProactive(command: SlackEventCommand, entrypoint: String) {
        if (!proactiveSemaphore.tryAcquire()) {
            logger.debug {
                "선행적 평가 건너뜀 (동시성 제한): channel=${command.channelId}"
            }
            metricsRecorder.recordDropped(
                entrypoint = entrypoint,
                reason = "proactive_concurrency",
                eventType = "message"
            )
            return
        }

        try {
            val responded = eventHandler.handleChannelMessage(command)
            metricsRecorder.recordHandler(
                entrypoint = entrypoint,
                eventType = "proactive",
                success = responded,
                durationMs = 0
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "선행적 핸들러 오류: channel=${command.channelId}" }
        } finally {
            proactiveSemaphore.release()
        }
    }

    private fun handleReactionEvent(event: JsonNode, entrypoint: String) {
        val tracker = botResponseTracker ?: return
        val item = event.path("item")
        if (item.path("type").asText() != "message") return

        val userId = event.path("user").asText()
        val reaction = event.path("reaction").asText()
        val channelId = item.path("channel").asText()
        val messageTs = item.path("ts").asText()

        if (userId.isBlank() || reaction.isBlank() || channelId.isBlank() || messageTs.isBlank()) return

        val tracked = tracker.lookup(channelId, messageTs) ?: return

        scope.launch {
            try {
                eventHandler.handleReaction(
                    userId = userId,
                    channelId = channelId,
                    messageTs = messageTs,
                    reaction = reaction,
                    sessionId = tracked.sessionId,
                    userPrompt = tracked.userPrompt
                )
                metricsRecorder.recordHandler(
                    entrypoint = entrypoint,
                    eventType = "reaction_feedback",
                    success = true,
                    durationMs = 0
                )
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "리액션 이벤트 처리 실패: channel=$channelId" }
            }
        }
    }

    /**
     * Agents & AI Apps의 assistant_thread_started 이벤트 처리.
     *
     * 사용자가 사이드바에서 봇을 클릭하여 새 대화를 시작하면 발생.
     * 스레드를 추적 대상에 등록하고, 환영 메시지로 응답한다.
     */
    private fun handleAssistantThreadStarted(event: JsonNode, entrypoint: String) {
        val thread = event.path("assistant_thread")
        val channelId = thread.path("channel_id").asText()
        val threadTs = thread.path("thread_ts").asText()
        val userId = thread.path("user_id").asText()

        if (channelId.isBlank() || threadTs.isBlank() || userId.isBlank()) {
            logger.debug { "assistant_thread_started 필수 필드 누락 — 무시" }
            return
        }

        logger.info { "Agents & AI Apps 스레드 시작: channel=$channelId, thread=$threadTs, user=$userId" }

        // 스레드 추적 등록 — 이후 message.im 이벤트가 이 스레드에서 처리됨
        threadTracker?.track(channelId, threadTs)

        scope.launch {
            try {
                messagingService.sendMessage(
                    channelId = channelId,
                    text = "안녕하세요! Reactor입니다. 무엇을 도와드릴까요? :wave:",
                    threadTs = threadTs
                )
                metricsRecorder.recordHandler(
                    entrypoint = entrypoint,
                    eventType = "assistant_thread_started",
                    success = true,
                    durationMs = 0
                )
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "assistant_thread_started 환영 메시지 전송 실패: channel=$channelId" }
            }
        }
    }

    override fun destroy() {
        scope.cancel()
    }

    companion object {
        const val BUSY_RESPONSE_TEXT = ":hourglass: The system is busy right now. Please try again in a moment."
        const val RATE_LIMITED_RESPONSE_TEXT = ":no_entry: Too many requests. Please wait a moment before trying again."
    }
}
