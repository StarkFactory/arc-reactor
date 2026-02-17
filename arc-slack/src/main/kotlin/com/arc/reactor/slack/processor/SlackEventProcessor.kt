package com.arc.reactor.slack.processor

import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.controller.SlackEventDeduplicator
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class SlackEventProcessor(
    private val eventHandler: SlackEventHandler,
    private val messagingService: SlackMessagingService,
    private val metricsRecorder: SlackMetricsRecorder,
    properties: SlackProperties
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val semaphore = Semaphore(properties.maxConcurrentRequests)
    private val requestTimeoutMs = properties.requestTimeoutMs
    private val failFastOnSaturation = properties.failFastOnSaturation
    private val notifyOnDrop = properties.notifyOnDrop
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
            logger.info { "Slack retry callback received: eventId=$eventId, retryNum=$retryNum, reason=$retryReason" }
        }

        if (eventId != null && deduplicator.isDuplicateAndMark(eventId)) {
            logger.info { "Duplicate Slack event ignored: eventId=$eventId, type=$eventType" }
            metricsRecorder.recordDuplicate(eventType)
            return
        }

        processEventAsync(event, eventType, entrypoint)
    }

    private fun processEventAsync(event: JsonNode, eventType: String, entrypoint: String) {
        if (event.path("bot_id").asText().isNotEmpty()) return
        if (event.path("subtype").asText().isNotEmpty()) return

        val command = SlackEventCommand(
            eventType = eventType,
            userId = event.path("user").asText(),
            channelId = event.path("channel").asText(),
            text = event.path("text").asText(),
            ts = event.path("ts").asText(),
            threadTs = event.path("thread_ts").asText().takeIf { it.isNotEmpty() }
        )

        if (command.userId.isBlank() || command.channelId.isBlank()) {
            logger.debug { "Skipping event with missing user or channel" }
            return
        }

        if (failFastOnSaturation && !semaphore.tryAcquire()) {
            logger.warn { "Slack event rejected due to saturation: entrypoint=$entrypoint, type=$eventType, channel=${command.channelId}" }
            metricsRecorder.recordDropped(
                entrypoint = entrypoint,
                reason = "queue_overflow",
                eventType = eventType
            )
            if (notifyOnDrop) {
                scope.launch { notifyBusyIfInteractive(command) }
            }
            return
        }

        scope.launch {
            if (!failFastOnSaturation) {
                val acquired = acquirePermitWithTimeout()
                if (!acquired) {
                    logger.warn { "Slack event dropped due to queue timeout: entrypoint=$entrypoint, type=$eventType, channel=${command.channelId}" }
                    metricsRecorder.recordDropped(
                        entrypoint = entrypoint,
                        reason = "queue_timeout",
                        eventType = eventType
                    )
                    if (notifyOnDrop) {
                        notifyBusyIfInteractive(command)
                    }
                    return@launch
                }
            }

            val started = System.currentTimeMillis()
            try {
                when (eventType) {
                    "app_mention" -> eventHandler.handleAppMention(command)
                    "message" -> {
                        if (command.threadTs != null) {
                            eventHandler.handleMessage(command)
                        }
                    }
                }
                metricsRecorder.recordHandler(
                    entrypoint = entrypoint,
                    eventType = eventType,
                    success = true,
                    durationMs = System.currentTimeMillis() - started
                )
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Failed to handle Slack event: entrypoint=$entrypoint, type=$eventType, channel=${command.channelId}" }
                metricsRecorder.recordHandler(
                    entrypoint = entrypoint,
                    eventType = eventType,
                    success = false,
                    durationMs = System.currentTimeMillis() - started
                )
            } finally {
                semaphore.release()
            }
        }
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
            logger.warn(e) { "Failed to send queue-timeout message for channel=${command.channelId}" }
        }
    }

    companion object {
        const val BUSY_RESPONSE_TEXT = ":hourglass: The system is busy right now. Please try again in a moment."
    }
}
