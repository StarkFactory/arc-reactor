package com.arc.reactor.slack.handler

import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * [SlackReminderStore]를 매분 폴링하여 만료된 리마인더를 DM으로 알림 전송하는 스케줄러.
 *
 * Slack `chat.postMessage`에 사용자 ID를 채널로 지정하여 DM을 전달한다.
 * 생성 시 자동 시작되며, [shutdown]을 호출하여 종료한다.
 *
 * @param reminderStore 리마인더 저장소
 * @param messagingService 메시지 전송 서비스
 * @param pollIntervalSeconds 폴링 간격 (초, 기본 60)
 * @see SlackReminderStore
 */
class SlackReminderScheduler(
    private val reminderStore: SlackReminderStore,
    private val messagingService: SlackMessagingService,
    pollIntervalSeconds: Long = 60
) : org.springframework.beans.factory.DisposableBean {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "slack-reminder-scheduler").apply { isDaemon = true }
    }

    init {
        executor.scheduleAtFixedRate(
            { pollAndNotify() },
            pollIntervalSeconds,
            pollIntervalSeconds,
            TimeUnit.SECONDS
        )
        logger.info { "리마인더 스케줄러 시작: interval=${pollIntervalSeconds}s" }
    }

    internal fun pollAndNotify() {
        val dueReminders = try {
            reminderStore.collectDueReminders()
        } catch (e: Exception) {
            logger.warn(e) { "만료 리마인더 수거 실패" }
            return
        }

        if (dueReminders.isEmpty()) return

        logger.info { "만료 리마인더 ${dueReminders.size}건 전송 시작" }
        for ((userId, reminder) in dueReminders) {
            scope.launch {
                try {
                    val result = messagingService.sendMessage(
                        channelId = userId,
                        text = ":bell: *Reminder #${reminder.id}*\n${reminder.text}"
                    )
                    if (result.ok) {
                        logger.debug { "리마인더 전송 완료: #${reminder.id} user=$userId" }
                    } else {
                        logger.warn { "리마인더 전송 실패: #${reminder.id} user=$userId error=${result.error}" }
                    }
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    logger.warn(e) { "리마인더 전송 중 오류: #${reminder.id} user=$userId" }
                }
            }
        }
    }

    override fun destroy() {
        shutdown()
    }

    fun shutdown() {
        executor.shutdownNow()
        scope.cancel()
        logger.info { "리마인더 스케줄러 종료" }
    }
}
