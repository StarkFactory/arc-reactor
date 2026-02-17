package com.arc.reactor.line.controller

import com.arc.reactor.line.config.LineProperties
import com.arc.reactor.line.handler.LineEventHandler
import com.arc.reactor.line.model.LineEventCommand
import com.arc.reactor.support.throwIfCancellation
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

private val logger = KotlinLogging.logger {}

/**
 * Handles incoming LINE webhook events.
 *
 * Supports text message events from users, groups, and rooms.
 * All heavy processing is done asynchronously to return 200 immediately.
 */
@RestController
@RequestMapping("/api/line")
@ConditionalOnProperty(prefix = "arc.reactor.line", name = ["enabled"], havingValue = "true")
@Tag(name = "LINE", description = "LINE webhook event handling endpoints")
class LineWebhookController(
    private val objectMapper: ObjectMapper,
    private val eventHandler: LineEventHandler,
    properties: LineProperties
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val semaphore = Semaphore(properties.maxConcurrentRequests)

    @PostMapping("/webhook")
    @Operation(summary = "Handle LINE webhook events")
    suspend fun handleWebhook(
        @RequestBody payload: String
    ): ResponseEntity<Any> {
        val json = objectMapper.readTree(payload)
        val events = json.path("events")

        if (events.isArray) {
            for (event in events) {
                processEventAsync(event)
            }
        }

        return ResponseEntity.ok().build()
    }

    private fun processEventAsync(event: JsonNode) {
        val eventType = event.path("type").asText()
        if (eventType != "message") return

        val message = event.path("message")
        val messageType = message.path("type").asText()
        if (messageType != "text") return

        val source = event.path("source")
        val userId = source.path("userId").asText()
        val replyToken = event.path("replyToken").asText()
        val text = message.path("text").asText()

        if (userId.isBlank() || text.isBlank()) {
            logger.debug { "Skipping event with missing userId or text" }
            return
        }

        val command = LineEventCommand(
            userId = userId,
            groupId = source.path("groupId").asText().takeIf { it.isNotEmpty() },
            roomId = source.path("roomId").asText().takeIf { it.isNotEmpty() },
            text = text,
            replyToken = replyToken,
            sourceType = source.path("type").asText(),
            messageId = message.path("id").asText()
        )

            scope.launch {
                semaphore.withPermit {
                    try {
                        eventHandler.handleMessage(command)
                    } catch (e: Exception) {
                        e.throwIfCancellation()
                        logger.error(e) {
                            "Failed to handle LINE event: user=${command.userId}"
                        }
                    }
            }
        }
    }
}
