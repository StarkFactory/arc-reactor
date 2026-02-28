package com.arc.reactor.slack.tools.config

import com.arc.reactor.slack.tools.tool.AddReactionTool
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
    val requiredScopes: Set<String>
)

private val slackToolScopeSpecsByType = mapOf(
    SendMessageTool::class to SlackToolScopeSpec("send_message", setOf("chat:write")),
    ReplyToThreadTool::class to SlackToolScopeSpec("reply_to_thread", setOf("chat:write")),
    ListChannelsTool::class to SlackToolScopeSpec("list_channels", setOf("channels:read")),
    FindChannelTool::class to SlackToolScopeSpec("find_channel", setOf("channels:read")),
    ReadMessagesTool::class to SlackToolScopeSpec("read_messages", setOf("channels:history")),
    ReadThreadRepliesTool::class to SlackToolScopeSpec("read_thread_replies", setOf("channels:history")),
    AddReactionTool::class to SlackToolScopeSpec("add_reaction", setOf("reactions:write")),
    GetUserInfoTool::class to SlackToolScopeSpec("get_user_info", setOf("users:read", "users:read.email")),
    FindUserTool::class to SlackToolScopeSpec("find_user", setOf("users:read")),
    SearchMessagesTool::class to SlackToolScopeSpec("search_messages", setOf("search:read")),
    UploadFileTool::class to SlackToolScopeSpec("upload_file", setOf("files:write"))
)

private class SlackScopeAwareLocalToolFilter(
    private val toolExposureResolver: ToolExposureResolver
) : LocalToolFilter {

    private val exposedToolNames: Set<String> by lazy {
        toolExposureResolver.resolveToolObjects(
            slackToolScopeSpecsByType.values.map { spec ->
                ToolCandidate(
                    name = spec.name,
                    requiredScopes = spec.requiredScopes,
                    toolObject = spec.name
                )
            }
        ).mapNotNull { it as? String }.toSet()
    }

    override fun filter(tools: List<LocalTool>): List<LocalTool> {
        if (tools.isEmpty()) return tools
        return tools.filter { tool ->
            val spec = slackToolScopeSpecsByType[tool::class]
            spec == null || spec.name in exposedToolNames
        }
    }
}

internal fun createSlackScopeAwareLocalToolFilter(
    toolExposureResolver: ToolExposureResolver
): LocalToolFilter = SlackScopeAwareLocalToolFilter(toolExposureResolver)
