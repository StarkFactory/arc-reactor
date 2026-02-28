package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.ListChannelsUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class ListChannelsTool(private val listChannelsUseCase: ListChannelsUseCase) : LocalTool {

    @Tool(description = "List Slack channels in the workspace. Returns channel ID, name, topic, member count, and privacy status.")
    fun list_channels(
        @ToolParam(description = "Maximum number of channels to return (default: 100)", required = false) limit: Int?,
        @ToolParam(description = "Pagination cursor for next page of results", required = false) cursor: String?
    ): String {
        val resolvedLimit = limit ?: DEFAULT_LIMIT
        if (resolvedLimit !in MIN_LIMIT..MAX_LIMIT) {
            return errorJson("limit must be between $MIN_LIMIT and $MAX_LIMIT")
        }

        val result = listChannelsUseCase.execute(
            limit = resolvedLimit,
            cursor = ToolInputValidation.normalizeOptional(cursor)
        )
        return toJson(result)
    }

    companion object {
        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 200
    }
}
