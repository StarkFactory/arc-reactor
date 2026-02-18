package com.arc.reactor.slack.processor

import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.service.SlackMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class SlackCommandProcessor(
    private val commandHandler: SlackCommandHandler,
    private val messagingService: SlackMessagingService,
    private val metricsRecorder: SlackMetricsRecorder,
    properties: SlackProperties
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val semaphore = Semaphore(properties.maxConcurrentRequests)
    private val requestTimeoutMs = properties.requestTimeoutMs
    private val failFastOnSaturation = properties.failFastOnSaturation
    private val notifyOnDrop = properties.notifyOnDrop

    fun submit(command: SlackSlashCommand, entrypoint: String): Boolean {
        metricsRecorder.recordInbound(entrypoint = entrypoint)
        return processAsync(command, entrypoint)
    }

    private fun processAsync(command: SlackSlashCommand, entrypoint: String): Boolean {
        if (failFastOnSaturation && !semaphore.tryAcquire()) {
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
            if (!failFastOnSaturation) {
                val acquired = acquirePermitWithTimeout()
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
                semaphore.release()
            }
        }
        return true
    }

    private suspend fun acquirePermitWithTimeout(): Boolean {
        if (requestTimeoutMs <= 0) {
            semaphore.acquire()
            return true
        }
        return withTimeoutOrNull(requestTimeoutMs) {
            semaphore.acquire()
            true
        } ?: false
    }

    companion object {
        const val PROCESSING_RESPONSE_TEXT = ":hourglass_flowing_sand: Processing..."
        const val BUSY_RESPONSE_TEXT = ":hourglass: The system is busy. Please try again shortly."
        const val INVALID_PAYLOAD_RESPONSE_TEXT = ":warning: Invalid slash command payload."
    }
}
