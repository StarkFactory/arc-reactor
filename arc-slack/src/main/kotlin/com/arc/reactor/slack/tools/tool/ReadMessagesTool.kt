package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.ReadMessagesUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class ReadMessagesTool(private val readMessagesUseCase: ReadMessagesUseCase) : LocalTool {

    @Tool(description = "Read recent messages from a Slack channel. Returns message text, sender, and timestamp.")
    fun read_messages(
        @ToolParam(description = "Slack channel ID to read messages from") channelId: String,
        @ToolParam(description = "Maximum number of messages to return (default: 10)", required = false) limit: Int?,
        @ToolParam(description = "Pagination cursor for the next page of messages", required = false) cursor: String?
    ): String {
        val normalizedChannelId = ToolInputValidation.normalizeChannelId(channelId)
            ?: return errorJson("channelId must be a valid Slack channel ID")
        val resolvedLimit = limit ?: DEFAULT_LIMIT
        if (resolvedLimit !in MIN_LIMIT..MAX_LIMIT) {
            return errorJson("limit must be between $MIN_LIMIT and $MAX_LIMIT")
        }

        val result = readMessagesUseCase.execute(
            channelId = normalizedChannelId,
            limit = resolvedLimit,
            cursor = ToolInputValidation.normalizeOptional(cursor)
        )
        return toJson(result)
    }

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 200
    }
}
