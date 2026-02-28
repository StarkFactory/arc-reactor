package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.ReadThreadRepliesUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class ReadThreadRepliesTool(private val readThreadRepliesUseCase: ReadThreadRepliesUseCase) : LocalTool {

    @Tool(description = "Read replies from a Slack thread. Returns reply text, sender, and timestamp.")
    fun read_thread_replies(
        @ToolParam(description = "Slack channel ID where the thread is") channelId: String,
        @ToolParam(description = "Thread timestamp of the parent message") threadTs: String,
        @ToolParam(description = "Maximum number of replies to return (default: 10)", required = false) limit: Int?,
        @ToolParam(description = "Pagination cursor for the next page of replies", required = false) cursor: String?
    ): String {
        val normalizedChannelId = ToolInputValidation.normalizeChannelId(channelId)
            ?: return errorJson("channelId must be a valid Slack channel ID")
        val normalizedThreadTs = ToolInputValidation.normalizeThreadTs(threadTs)
            ?: return errorJson("threadTs must be a valid Slack timestamp")

        val resolvedLimit = limit ?: DEFAULT_LIMIT
        if (resolvedLimit !in MIN_LIMIT..MAX_LIMIT) {
            return errorJson("limit must be between $MIN_LIMIT and $MAX_LIMIT")
        }

        val result = readThreadRepliesUseCase.execute(
            channelId = normalizedChannelId,
            threadTs = normalizedThreadTs,
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
