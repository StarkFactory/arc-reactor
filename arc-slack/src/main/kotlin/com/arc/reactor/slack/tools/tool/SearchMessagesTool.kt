package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.SearchMessagesUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class SearchMessagesTool(private val searchMessagesUseCase: SearchMessagesUseCase) : LocalTool {

    @Tool(description = "Search Slack messages by query text.")
    fun search_messages(
        @ToolParam(description = "Search query text") query: String,
        @ToolParam(description = "Results per page (default: 20, max: 100)", required = false) count: Int?,
        @ToolParam(description = "Page number (default: 1)", required = false) page: Int?
    ): String {
        val normalizedQuery = ToolInputValidation.normalizeRequired(query) ?: return errorJson("query is required")
        val resolvedCount = count ?: DEFAULT_COUNT
        if (resolvedCount !in MIN_COUNT..MAX_COUNT) {
            return errorJson("count must be between $MIN_COUNT and $MAX_COUNT")
        }
        val resolvedPage = page ?: DEFAULT_PAGE
        if (resolvedPage < MIN_PAGE) {
            return errorJson("page must be at least $MIN_PAGE")
        }

        val result = searchMessagesUseCase.execute(
            query = normalizedQuery,
            count = resolvedCount,
            page = resolvedPage
        )
        return toJson(result)
    }

    companion object {
        private const val DEFAULT_COUNT = 20
        private const val MIN_COUNT = 1
        private const val MAX_COUNT = 100
        private const val DEFAULT_PAGE = 1
        private const val MIN_PAGE = 1
    }
}
