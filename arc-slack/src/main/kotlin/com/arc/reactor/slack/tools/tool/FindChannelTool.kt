package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.FindChannelUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class FindChannelTool(private val findChannelUseCase: FindChannelUseCase) : LocalTool {

    @Tool(description = "Find Slack channels by name. Supports exact or partial matching.")
    fun find_channel(
        @ToolParam(description = "Channel name query") query: String,
        @ToolParam(description = "Use exact name match (default: false)", required = false) exactMatch: Boolean?,
        @ToolParam(description = "Maximum number of channels to return (default: 10)", required = false) limit: Int?
    ): String {
        val normalizedQuery = ToolInputValidation.normalizeRequired(query) ?: return errorJson("query is required")

        val resolvedLimit = limit ?: DEFAULT_LIMIT
        if (resolvedLimit !in MIN_LIMIT..MAX_LIMIT) {
            return errorJson("limit must be between $MIN_LIMIT and $MAX_LIMIT")
        }

        val result = findChannelUseCase.execute(
            query = normalizedQuery,
            exactMatch = exactMatch ?: false,
            limit = resolvedLimit
        )
        return toJson(result)
    }

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 50
    }
}
