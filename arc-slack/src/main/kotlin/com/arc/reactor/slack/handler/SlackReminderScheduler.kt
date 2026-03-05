package com.arc.reactor.slack.handler

import com.arc.reactor.slack.service.SlackMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Polls [SlackReminderStore] every minute for due reminders and sends DM notifications.
 *
 * Uses Slack `chat.postMessage` with user ID as channel to deliver DMs.
 * Automatically started when constructed (call [shutdown] to stop).
 */
class SlackReminderScheduler(
    private val reminderStore: SlackReminderStore,
    private val messagingService: SlackMessagingService,
    pollIntervalSeconds: Long = 60
) {
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
        logger.info { "SlackReminderScheduler started (interval=${pollIntervalSeconds}s)" }
    }

    internal fun pollAndNotify() {
        val dueReminders = try {
            reminderStore.collectDueReminders()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to collect due reminders" }
            return
        }

        if (dueReminders.isEmpty()) return

        logger.info { "Delivering ${dueReminders.size} due reminder(s)" }
        for ((userId, reminder) in dueReminders) {
            scope.launch {
                try {
                    val result = messagingService.sendMessage(
                        channelId = userId,
                        text = ":bell: *Reminder #${reminder.id}*\n${reminder.text}"
                    )
                    if (result.ok) {
                        logger.debug { "Reminder #${reminder.id} delivered to user=$userId" }
                    } else {
                        logger.warn { "Failed to deliver reminder #${reminder.id} to user=$userId: ${result.error}" }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Error delivering reminder #${reminder.id} to user=$userId" }
                }
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        logger.info { "SlackReminderScheduler shut down" }
    }
}
