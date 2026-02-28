package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.ReplyToThreadUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class ReplyToThreadTool(
    private val replyToThreadUseCase: ReplyToThreadUseCase,
    private val idempotencyService: WriteOperationIdempotencyService = NoopWriteOperationIdempotencyService
) : LocalTool {

    @Tool(description = "Reply to a specific thread in a Slack channel. Requires the thread timestamp of the parent message.")
    fun reply_to_thread(
        @ToolParam(description = "Slack channel ID where the thread is") channelId: String,
        @ToolParam(description = "Thread timestamp of the parent message") threadTs: String,
        @ToolParam(description = "Reply text to send") text: String,
        @ToolParam(description = "Optional idempotency key to prevent duplicate writes", required = false) idempotencyKey: String? = null
    ): String {
        val normalizedChannelId = ToolInputValidation.normalizeChannelId(channelId)
            ?: return errorJson("channelId must be a valid Slack channel ID")
        val normalizedThreadTs = ToolInputValidation.normalizeThreadTs(threadTs)
            ?: return errorJson("threadTs must be a valid Slack timestamp")
        val normalizedText = ToolInputValidation.normalizeRequired(text)
            ?: return errorJson("text is required")
        val dedupeKey = ToolInputValidation.normalizeOptional(idempotencyKey)

        return idempotencyService.execute(
            toolName = "reply_to_thread",
            explicitIdempotencyKey = dedupeKey,
            keyParts = listOf(normalizedChannelId, normalizedThreadTs, normalizedText)
        ) {
            toJson(replyToThreadUseCase.execute(normalizedChannelId, normalizedThreadTs, normalizedText))
        }
    }
}
