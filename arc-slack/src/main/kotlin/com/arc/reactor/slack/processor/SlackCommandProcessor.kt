package com.arc.reactor.slack.processor

import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.resilience.SlackUserRateLimiter
import com.arc.reactor.slack.service.SlackMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.beans.factory.DisposableBean
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 슬래시 명령 비동기 처리기.
 *
 * Events API 또는 Socket Mode에서 수신된 슬래시 명령을 비동기로 처리하며,
 * backpressure 제어를 통해 과부하를 방지한다.
 *
 * 흐름:
 * 1. [submit] 호출 -> backpressure 확인
 * 2. 허용 시 코루틴으로 [SlackCommandHandler.handleSlashCommand] 실행
 * 3. 거부 시 response_url로 "busy" 메시지 전송
 *
 * @param commandHandler 실제 슬래시 명령 처리 핸들러
 * @param messagingService 메시지 전송 서비스 (드롭 알림용)
 * @param metricsRecorder 메트릭 기록기
 * @param properties Slack 설정 (동시성, 타임아웃 등)
 * @see SlackCommandHandler
 * @see SlackBackpressureLimiter
 */
class SlackCommandProcessor(
    private val commandHandler: SlackCommandHandler,
    private val messagingService: SlackMessagingService,
    private val metricsRecorder: SlackMetricsRecorder,
    properties: SlackProperties,
    private val userRateLimiter: SlackUserRateLimiter? = null
) : DisposableBean {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val backpressureLimiter = SlackBackpressureLimiter(
        maxConcurrentRequests = properties.maxConcurrentRequests,
        requestTimeoutMs = properties.requestTimeoutMs,
        failFastOnSaturation = properties.failFastOnSaturation
    )
    private val notifyOnDrop = properties.notifyOnDrop

    fun submit(command: SlackSlashCommand, entrypoint: String): Boolean {
        metricsRecorder.recordInbound(entrypoint = entrypoint)
        return processAsync(command, entrypoint)
    }

    private fun processAsync(command: SlackSlashCommand, entrypoint: String): Boolean {
        if (userRateLimiter != null && !userRateLimiter.tryAcquire(command.userId)) {
            metricsRecorder.recordDropped(
                entrypoint = entrypoint,
                reason = "user_rate_limited",
                eventType = command.command
            )
            scope.launch {
                messagingService.sendResponseUrl(
                    responseUrl = command.responseUrl,
                    text = RATE_LIMITED_RESPONSE_TEXT
                )
            }
            return false
        }

        if (backpressureLimiter.rejectImmediatelyIfConfigured()) {
            logger.warn {
                "Slack slash command rejected due to saturation: " +
                    "entrypoint=$entrypoint, channel=${command.channelId}"
            }
            metricsRecorder.recordDropped(
                entrypoint = entrypoint,
                reason = "queue_overflow",
                eventType = command.command
            )
            return false
        }

        scope.launch {
            val acquired = backpressureLimiter.acquireForQueuedMode()
            if (!acquired) {
                logger.warn {
                    "Slack slash command dropped due to queue timeout: " +
                        "entrypoint=$entrypoint, channel=${command.channelId}"
                }
                metricsRecorder.recordDropped(
                    entrypoint = entrypoint,
                    reason = "queue_timeout",
                    eventType = command.command
                )
                if (notifyOnDrop) {
                    messagingService.sendResponseUrl(
                        responseUrl = command.responseUrl,
                        text = BUSY_RESPONSE_TEXT
                    )
                }
                return@launch
            }

            val started = System.currentTimeMillis()
            try {
                commandHandler.handleSlashCommand(command)
                metricsRecorder.recordHandler(
                    entrypoint = entrypoint,
                    eventType = command.command,
                    success = true,
                    durationMs = System.currentTimeMillis() - started
                )
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Failed to handle slash command for channel=${command.channelId}" }
                metricsRecorder.recordHandler(
                    entrypoint = entrypoint,
                    eventType = command.command,
                    success = false,
                    durationMs = System.currentTimeMillis() - started
                )
            } finally {
                backpressureLimiter.release()
            }
        }
        return true
    }

    override fun destroy() {
        scope.cancel()
    }

    companion object {
        const val PROCESSING_RESPONSE_TEXT = ":hourglass_flowing_sand: Processing..."
        const val BUSY_RESPONSE_TEXT = ":hourglass: The system is busy. Please try again shortly."
        const val INVALID_PAYLOAD_RESPONSE_TEXT = ":warning: Invalid slash command payload."
        const val RATE_LIMITED_RESPONSE_TEXT = ":no_entry: Too many requests. Please wait a moment before trying again."
    }
}
