package com.arc.reactor.slack.controller

import com.arc.reactor.slack.model.SlackChallengeResponse
import com.arc.reactor.slack.processor.SlackEventProcessor
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
@ConditionalOnProperty(
    prefix = "arc.reactor.slack",
    name = ["transport-mode"],
    havingValue = "events_api",
    matchIfMissing = true
)
@Tag(name = "Slack", description = "Slack event handling endpoints")
class SlackEventController(
    private val objectMapper: ObjectMapper,
    private val eventProcessor: SlackEventProcessor
) {
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

        eventProcessor.submitEventCallback(
            payload = json,
            entrypoint = "events_api",
            retryNum = retryNum,
            retryReason = retryReason
        )
        return ResponseEntity.ok().build()
    }
}
