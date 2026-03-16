package com.arc.reactor.slack.controller

import com.arc.reactor.slack.model.SlackCommandAckResponse
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.processor.SlackCommandProcessor
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Slack 슬래시 명령 엔드포인트 컨트롤러.
 *
 * Slack에서 전송된 슬래시 명령을 수신하고 즉시 확인 응답(ACK)을 반환한 뒤,
 * [SlackCommandProcessor]를 통해 비동기로 처리한다.
 * Slack의 3초 응답 요구사항을 충족하기 위해 비동기 패턴을 사용한다.
 *
 * Events API 전송 모드(`transport-mode=events_api`)에서만 활성화된다.
 *
 * @see SlackCommandProcessor
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
@Tag(name = "Slack", description = "Slack command handling endpoints")
class SlackCommandController(
    private val commandProcessor: SlackCommandProcessor
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

    @PostMapping("/commands", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    @Operation(summary = "Handle Slack slash command")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Command acknowledged"),
        ApiResponse(responseCode = "400", description = "Invalid or missing required command fields")
    ])
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
                    text = SlackCommandProcessor.INVALID_PAYLOAD_RESPONSE_TEXT
                )
            )
        }

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

        val accepted = commandProcessor.submit(
            command = slashCommand,
            entrypoint = "slash_command"
        )
        if (!accepted) {
            return ResponseEntity.ok(
                SlackCommandAckResponse(
                    responseType = "ephemeral",
                    text = SlackCommandProcessor.BUSY_RESPONSE_TEXT
                )
            )
        }

        return ResponseEntity.ok(
            SlackCommandAckResponse(
                responseType = "ephemeral",
                text = SlackCommandProcessor.PROCESSING_RESPONSE_TEXT
            )
        )
    }
}
