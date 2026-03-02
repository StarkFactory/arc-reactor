package com.arc.reactor.slack.adapter

import com.arc.reactor.scheduler.SlackMessageSender
import com.arc.reactor.slack.service.SlackMessagingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Bridges the scheduler's [SlackMessageSender] interface to [SlackMessagingService].
 *
 * Uses `runBlocking(Dispatchers.IO)` because the scheduler executes jobs
 * on a [TaskScheduler] thread pool (non-coroutine context).
 */
class SlackMessageSenderAdapter(
    private val messagingService: SlackMessagingService
) : SlackMessageSender {

    override fun sendMessage(channelId: String, text: String) {
        val result = runBlocking(Dispatchers.IO) {
            messagingService.sendMessage(channelId, text)
        }
        if (!result.ok) {
            logger.warn { "Slack API returned error for channel=$channelId: ${result.error}" }
        }
    }
}
