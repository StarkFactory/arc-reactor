package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.GetUserInfoUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class GetUserInfoTool(private val getUserInfoUseCase: GetUserInfoUseCase) : LocalTool {

    @Tool(description = "Get information about a Slack user. Returns name, display name, email, and bot status.")
    fun get_user_info(
        @ToolParam(description = "Slack user ID (e.g., U0AA1LRGUGY)") userId: String
    ): String {
        val normalizedUserId = ToolInputValidation.normalizeUserId(userId)
            ?: return errorJson("userId must be a valid Slack user ID")

        val result = getUserInfoUseCase.execute(normalizedUserId)
        return toJson(result)
    }
}
