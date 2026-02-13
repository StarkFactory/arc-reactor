package com.arc.reactor.slack.controller

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackCommandAckResponse
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.service.SlackMessagingService
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
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
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
    private val messagingService: SlackMessagingService,
    private val metricsRecorder: SlackMetricsRecorder,
    properties: SlackProperties
) {
    data class SlackSlashCommandForm(
        val command: String? = null,
        val text: String? = null,
        val user_id: String? = null,
        val user_name: String? = null,
        val channel_id: String? = null,
        val channel_name: String? = null,
        val response_url: String? = null,
        val trigger_id: String? = null
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val semaphore = Semaphore(properties.maxConcurrentRequests)
    private val requestTimeoutMs = properties.requestTimeoutMs

    @PostMapping("/commands", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    @Operation(summary = "Handle Slack slash command")
    fun handleSlashCommandEndpoint(
        @ModelAttribute form: SlackSlashCommandForm
    ): ResponseEntity<SlackCommandAckResponse> = handleSlashCommand(
        command = form.command,
        text = form.text,
        userId = form.user_id,
        userName = form.user_name,
        channelId = form.channel_id,
        channelName = form.channel_name,
        responseUrl = form.response_url,
        triggerId = form.trigger_id
    )

    fun handleSlashCommand(
        command: String?,
        text: String?,
        userId: String?,
        userName: String?,
        channelId: String?,
        channelName: String?,
        responseUrl: String?,
        triggerId: String?
    ): ResponseEntity<SlackCommandAckResponse> {
        if (command.isNullOrBlank() || userId.isNullOrBlank() ||
            channelId.isNullOrBlank() || responseUrl.isNullOrBlank()
        ) {
            return ResponseEntity.badRequest().body(
                SlackCommandAckResponse(
                    responseType = "ephemeral",
                    text = ":warning: Invalid slash command payload."
                )
            )
        }

        metricsRecorder.recordInbound(entrypoint = "slash_command")

        val slashCommand = SlackSlashCommand(
            command = command,
            text = text.orEmpty(),
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
            val acquired = acquirePermitWithTimeout()
            if (!acquired) {
                logger.warn { "Slack slash command dropped due to queue timeout: channel=${command.channelId}" }
                metricsRecorder.recordDropped(
                    entrypoint = "slash_command",
                    reason = "queue_timeout"
                )
                messagingService.sendResponseUrl(
                    responseUrl = command.responseUrl,
                    text = ":hourglass: The system is busy. Please try again shortly."
                )
                return@launch
            }

            val started = System.currentTimeMillis()
            try {
                commandHandler.handleSlashCommand(command)
                metricsRecorder.recordHandler(
                    entrypoint = "slash_command",
                    eventType = command.command,
                    success = true,
                    durationMs = System.currentTimeMillis() - started
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to handle slash command for channel=${command.channelId}" }
                metricsRecorder.recordHandler(
                    entrypoint = "slash_command",
                    eventType = command.command,
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
}
