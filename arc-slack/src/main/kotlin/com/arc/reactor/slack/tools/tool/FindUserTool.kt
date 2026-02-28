package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.FindUserUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class FindUserTool(private val findUserUseCase: FindUserUseCase) : LocalTool {

    @Tool(description = "Find Slack users by name, display name, or real name. Supports exact or partial matching.")
    fun find_user(
        @ToolParam(description = "User name query") query: String,
        @ToolParam(description = "Use exact name match (default: false)", required = false) exactMatch: Boolean?,
        @ToolParam(description = "Maximum number of users to return (default: 10)", required = false) limit: Int?
    ): String {
        val normalizedQuery = ToolInputValidation.normalizeRequired(query) ?: return errorJson("query is required")
        val resolvedLimit = limit ?: DEFAULT_LIMIT
        if (resolvedLimit !in MIN_LIMIT..MAX_LIMIT) {
            return errorJson("limit must be between $MIN_LIMIT and $MAX_LIMIT")
        }

        val result = findUserUseCase.execute(
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
