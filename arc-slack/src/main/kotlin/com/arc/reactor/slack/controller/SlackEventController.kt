package com.arc.reactor.slack.controller

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.model.SlackChallengeResponse
import com.arc.reactor.slack.model.SlackEventCommand
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.RequestBody
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
    properties: SlackProperties
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val semaphore = Semaphore(properties.maxConcurrentRequests)

    @PostMapping("/events")
    @Operation(summary = "Handle Slack events (webhook endpoint)")
    suspend fun handleEvent(@RequestBody payload: String): ResponseEntity<Any> {
        val json = objectMapper.readTree(payload)

        // URL verification challenge
        if (json.has("challenge")) {
            val challenge = json.path("challenge").asText()
            logger.info { "Slack URL verification challenge received" }
            return ResponseEntity.ok(SlackChallengeResponse(challenge))
        }

        val event = json.path("event")
        val eventType = event.path("type").asText()

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
            semaphore.withPermit {
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Failed to handle Slack event: type=$eventType, channel=${command.channelId}" }
                }
            }
        }
    }
}
