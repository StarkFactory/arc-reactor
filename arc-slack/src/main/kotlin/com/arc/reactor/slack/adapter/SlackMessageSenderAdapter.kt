package com.arc.reactor.slack.adapter

import com.arc.reactor.scheduler.SlackMessageSender
import com.arc.reactor.slack.service.SlackMessagingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 스케줄러의 [SlackMessageSender] 인터페이스와 [SlackMessagingService]를 연결하는 어댑터.
 *
 * 스케줄러는 [TaskScheduler] 스레드 풀(비코루틴 컨텍스트)에서 작업을 실행하므로,
 * `runBlocking(Dispatchers.IO)`로 suspend 함수를 호출한다.
 *
 * @see SlackMessagingService
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
