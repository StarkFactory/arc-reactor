package com.arc.reactor.slack.controller

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackChallengeResponse
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Handles incoming Slack events (webhook endpoint).
 *
 * Supports:
 * - URL verification challenge (Slack setup)
 * - app_mention events (bot mentioned in channel)
 * - message events (thread replies)
 *
 * All heavy processing is done asynchronously to meet Slack's 3-second response requirement.
 */
@RestController
@RequestMapping("/api/slack")
@ConditionalOnProperty(prefix = "arc.reactor.slack", name = ["enabled"], havingValue = "true")
@Tag(name = "Slack", description = "Slack event handling endpoints")
class SlackEventController(
    private val objectMapper: ObjectMapper,
    private val eventHandler: SlackEventHandler,
    private val messagingService: SlackMessagingService,
    private val metricsRecorder: SlackMetricsRecorder,
    properties: SlackProperties
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val semaphore = Semaphore(properties.maxConcurrentRequests)
    private val requestTimeoutMs = properties.requestTimeoutMs
    private val deduplicator = SlackEventDeduplicator(
        enabled = properties.eventDedupEnabled,
        ttlSeconds = properties.eventDedupTtlSeconds,
        maxEntries = properties.eventDedupMaxEntries
    )

    @PostMapping("/events")
    @Operation(summary = "Handle Slack events (webhook endpoint)")
    suspend fun handleEvent(
        @RequestBody payload: String,
        @RequestHeader(name = "X-Slack-Retry-Num", required = false) retryNum: String? = null,
        @RequestHeader(name = "X-Slack-Retry-Reason", required = false) retryReason: String? = null
    ): ResponseEntity<Any> {
        val json = objectMapper.readTree(payload)

        // URL verification challenge
        if (json.has("challenge")) {
            val challenge = json.path("challenge").asText()
            logger.info { "Slack URL verification challenge received" }
            return ResponseEntity.ok(SlackChallengeResponse(challenge))
        }

        val event = json.path("event")
        val eventType = event.path("type").asText()
        metricsRecorder.recordInbound(entrypoint = "events_api", eventType = eventType)
        val eventId = json.path("event_id").asText().takeIf { it.isNotBlank() }

        if (retryNum != null || retryReason != null) {
            logger.info { "Slack retry callback received: eventId=$eventId, retryNum=$retryNum, reason=$retryReason" }
        }

        if (eventId != null && deduplicator.isDuplicateAndMark(eventId)) {
            logger.info { "Duplicate Slack event ignored: eventId=$eventId, type=$eventType" }
            metricsRecorder.recordDuplicate(eventType)
            return ResponseEntity.ok().build()
        }

        // Return 200 immediately (Slack 3-second constraint)
        processEventAsync(event, eventType)

        return ResponseEntity.ok().build()
    }

    private fun processEventAsync(event: JsonNode, eventType: String) {
        // Filter bot messages to prevent loops
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

        scope.launch {
            val acquired = acquirePermitWithTimeout()
            if (!acquired) {
                logger.warn { "Slack event dropped due to queue timeout: type=$eventType, channel=${command.channelId}" }
                metricsRecorder.recordDropped(
                    entrypoint = "events_api",
                    reason = "queue_timeout",
                    eventType = eventType
                )
                notifyBusyIfInteractive(command)
                return@launch
            }

            val started = System.currentTimeMillis()
            try {
                when (eventType) {
                    "app_mention" -> eventHandler.handleAppMention(command)
                    "message" -> {
                        // Only handle thread messages (follow-up in existing conversation)
                        if (command.threadTs != null) {
                            eventHandler.handleMessage(command)
                        }
                    }
                }
                metricsRecorder.recordHandler(
                    entrypoint = "events_api",
                    eventType = eventType,
                    success = true,
                    durationMs = System.currentTimeMillis() - started
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to handle Slack event: type=$eventType, channel=${command.channelId}" }
                metricsRecorder.recordHandler(
                    entrypoint = "events_api",
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
                text = ":hourglass: The system is busy right now. Please try again in a moment.",
                threadTs = threadTs
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send queue-timeout message for channel=${command.channelId}" }
        }
    }
}
