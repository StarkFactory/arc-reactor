package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.SendMessageUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class SendMessageTool(
    private val sendMessageUseCase: SendMessageUseCase,
    private val idempotencyService: WriteOperationIdempotencyService = NoopWriteOperationIdempotencyService
) : LocalTool {

    @Tool(description = "Send a message to a Slack channel. Optionally send as a thread reply by providing threadTs.")
    fun send_message(
        @ToolParam(description = "Slack channel ID to send the message to") channelId: String,
        @ToolParam(description = "Message text to send") text: String,
        @ToolParam(description = "Thread timestamp to reply to (optional)", required = false) threadTs: String?,
        @ToolParam(description = "Optional idempotency key to prevent duplicate writes", required = false) idempotencyKey: String? = null
    ): String {
        val normalizedChannelId = ToolInputValidation.normalizeChannelId(channelId)
            ?: return errorJson("channelId must be a valid Slack channel ID")
        val normalizedText = ToolInputValidation.normalizeRequired(text)
            ?: return errorJson("text is required")
        val normalizedThreadTs = threadTs?.let {
            ToolInputValidation.normalizeThreadTs(it)
                ?: return errorJson("threadTs must be a valid Slack timestamp")
        }
        val dedupeKey = ToolInputValidation.normalizeOptional(idempotencyKey)

        return idempotencyService.execute(
            toolName = "send_message",
            explicitIdempotencyKey = dedupeKey,
            keyParts = listOf(normalizedChannelId, normalizedText, normalizedThreadTs.orEmpty())
        ) {
            toJson(sendMessageUseCase.execute(normalizedChannelId, normalizedText, normalizedThreadTs))
        }
    }
}
