package com.arc.reactor.slack.tools.config

import com.arc.reactor.slack.tools.tool.AddReactionTool
import com.arc.reactor.slack.tools.tool.AppendCanvasTool
import com.arc.reactor.slack.tools.tool.CreateCanvasTool
import com.arc.reactor.slack.tools.tool.FindChannelTool
import com.arc.reactor.slack.tools.tool.FindUserTool
import com.arc.reactor.slack.tools.tool.GetUserInfoTool
import com.arc.reactor.slack.tools.tool.ListChannelsTool
import com.arc.reactor.slack.tools.tool.ReadMessagesTool
import com.arc.reactor.slack.tools.tool.ReadThreadRepliesTool
import com.arc.reactor.slack.tools.tool.ReplyToThreadTool
import com.arc.reactor.slack.tools.tool.SearchMessagesTool
import com.arc.reactor.slack.tools.tool.SendMessageTool
import com.arc.reactor.slack.tools.tool.UploadFileTool
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.LocalToolFilter

private data class SlackToolScopeSpec(
    val name: String,
    val requiredScopes: Set<String> = emptySet(),
    val requiredAnyScopes: Set<String> = emptySet()
)

private fun channelReadScopes(mode: ConversationScopeMode): Set<String> {
    return when (mode) {
        ConversationScopeMode.PUBLIC_ONLY -> setOf("channels:read")
        ConversationScopeMode.INCLUDE_PRIVATE_AND_DM -> setOf(
            "channels:read",
            "groups:read",
            "im:read",
            "mpim:read"
        )
    }
}

private fun historyScopes(mode: ConversationScopeMode): Set<String> {
    return when (mode) {
        ConversationScopeMode.PUBLIC_ONLY -> setOf("channels:history")
        ConversationScopeMode.INCLUDE_PRIVATE_AND_DM -> setOf(
            "channels:history",
            "groups:history",
            "im:history",
            "mpim:history"
        )
    }
}

private fun slackToolScopeSpecsByType(mode: ConversationScopeMode) = mapOf(
    SendMessageTool::class to SlackToolScopeSpec("send_message", requiredScopes = setOf("chat:write")),
    ReplyToThreadTool::class to SlackToolScopeSpec("reply_to_thread", requiredScopes = setOf("chat:write")),
    ListChannelsTool::class to SlackToolScopeSpec("list_channels", requiredAnyScopes = channelReadScopes(mode)),
    FindChannelTool::class to SlackToolScopeSpec("find_channel", requiredAnyScopes = channelReadScopes(mode)),
    ReadMessagesTool::class to SlackToolScopeSpec("read_messages", requiredAnyScopes = historyScopes(mode)),
    ReadThreadRepliesTool::class to SlackToolScopeSpec("read_thread_replies", requiredAnyScopes = historyScopes(mode)),
    AddReactionTool::class to SlackToolScopeSpec("add_reaction", requiredScopes = setOf("reactions:write")),
    GetUserInfoTool::class to SlackToolScopeSpec(
        "get_user_info",
        requiredScopes = setOf("users:read", "users:read.email")
    ),
    FindUserTool::class to SlackToolScopeSpec("find_user", requiredScopes = setOf("users:read")),
    SearchMessagesTool::class to SlackToolScopeSpec("search_messages", requiredScopes = setOf("search:read")),
    UploadFileTool::class to SlackToolScopeSpec("upload_file", requiredScopes = setOf("files:write")),
    CreateCanvasTool::class to SlackToolScopeSpec("create_canvas", requiredScopes = setOf("canvases:write")),
    AppendCanvasTool::class to SlackToolScopeSpec("append_canvas", requiredScopes = setOf("canvases:write"))
)

private class SlackScopeAwareLocalToolFilter(
    private val properties: SlackToolsProperties,
    private val toolExposureResolver: ToolExposureResolver
) : LocalToolFilter {
    private val scopeSpecsByType = slackToolScopeSpecsByType(properties.toolExposure.conversationScopeMode)

    private val exposedToolNames: Set<String> by lazy {
        toolExposureResolver.resolveToolObjects(
            scopeSpecsByType.values.map { spec ->
                ToolCandidate(
                    name = spec.name,
                    requiredScopes = spec.requiredScopes,
                    requiredAnyScopes = spec.requiredAnyScopes,
                    toolObject = spec.name
                )
            }
        ).mapNotNull { it as? String }.toSet()
    }

    override fun filter(tools: List<LocalTool>): List<LocalTool> {
        if (tools.isEmpty()) return tools
        return tools.filter { tool ->
            val spec = scopeSpecsByType[tool::class]
            spec == null || spec.name in exposedToolNames
        }
    }
}

internal fun createSlackScopeAwareLocalToolFilter(
    properties: SlackToolsProperties,
    toolExposureResolver: ToolExposureResolver
): LocalToolFilter = SlackScopeAwareLocalToolFilter(properties, toolExposureResolver)
