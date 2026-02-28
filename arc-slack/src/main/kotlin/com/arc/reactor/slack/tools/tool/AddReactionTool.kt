package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.AddReactionUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class AddReactionTool(
    private val addReactionUseCase: AddReactionUseCase,
    private val idempotencyService: WriteOperationIdempotencyService = NoopWriteOperationIdempotencyService
) : LocalTool {

    @Tool(description = "Add an emoji reaction to a Slack message. Use emoji names without colons (e.g., 'thumbsup' not ':thumbsup:').")
    fun add_reaction(
        @ToolParam(description = "Slack channel ID where the message is") channelId: String,
        @ToolParam(description = "Timestamp of the message to react to") timestamp: String,
        @ToolParam(description = "Emoji name without colons (e.g., thumbsup, heart, eyes)") emoji: String,
        @ToolParam(description = "Optional idempotency key to prevent duplicate writes", required = false) idempotencyKey: String? = null
    ): String {
        val normalizedChannelId = ToolInputValidation.normalizeChannelId(channelId)
            ?: return errorJson("channelId must be a valid Slack channel ID")
        val normalizedTimestamp = ToolInputValidation.normalizeThreadTs(timestamp)
            ?: return errorJson("timestamp must be a valid Slack timestamp")
        val normalizedEmoji = ToolInputValidation.normalizeEmoji(emoji)
            ?: return errorJson("emoji must contain letters, numbers, underscore, plus or dash")
        val dedupeKey = ToolInputValidation.normalizeOptional(idempotencyKey)

        return idempotencyService.execute(
            toolName = "add_reaction",
            explicitIdempotencyKey = dedupeKey,
            keyParts = listOf(normalizedChannelId, normalizedTimestamp, normalizedEmoji)
        ) {
            toJson(addReactionUseCase.execute(normalizedChannelId, normalizedTimestamp, normalizedEmoji))
        }
    }
}
