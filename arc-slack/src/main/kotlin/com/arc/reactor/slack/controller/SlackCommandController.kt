package com.arc.reactor.slack.controller

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.model.SlackCommandAckResponse
import com.arc.reactor.slack.model.SlackSlashCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Handles Slack slash commands.
 *
 * Returns immediate acknowledgement and processes command asynchronously.
 */
@RestController
@RequestMapping("/api/slack")
@ConditionalOnProperty(prefix = "arc.reactor.slack", name = ["enabled"], havingValue = "true")
@Tag(name = "Slack", description = "Slack command handling endpoints")
class SlackCommandController(
    private val commandHandler: SlackCommandHandler,
    properties: SlackProperties
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val semaphore = Semaphore(properties.maxConcurrentRequests)

    @PostMapping("/commands", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    @Operation(summary = "Handle Slack slash command")
    fun handleSlashCommand(
        @RequestParam("command") command: String,
        @RequestParam("text", required = false, defaultValue = "") text: String,
        @RequestParam("user_id") userId: String,
        @RequestParam("user_name", required = false) userName: String?,
        @RequestParam("channel_id") channelId: String,
        @RequestParam("channel_name", required = false) channelName: String?,
        @RequestParam("response_url") responseUrl: String,
        @RequestParam("trigger_id", required = false) triggerId: String?
    ): ResponseEntity<SlackCommandAckResponse> {
        val slashCommand = SlackSlashCommand(
            command = command,
            text = text,
            userId = userId,
            userName = userName,
            channelId = channelId,
            channelName = channelName,
            responseUrl = responseUrl,
            triggerId = triggerId
        )

        processAsync(slashCommand)

        return ResponseEntity.ok(
            SlackCommandAckResponse(
                responseType = "ephemeral",
                text = ":hourglass_flowing_sand: Processing..."
            )
        )
    }

    private fun processAsync(command: SlackSlashCommand) {
        scope.launch {
            semaphore.withPermit {
                try {
                    commandHandler.handleSlashCommand(command)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Failed to handle slash command for channel=${command.channelId}" }
                }
            }
        }
    }
}
