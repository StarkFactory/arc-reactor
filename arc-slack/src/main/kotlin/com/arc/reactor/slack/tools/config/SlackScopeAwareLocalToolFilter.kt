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

/**
 * Slack 도구의 필수 OAuth 스코프를 정의하는 스펙.
 *
 * @param name 도구 이름
 * @param requiredScopes 모두 필요한 스코프 (AND 조건)
 * @param requiredAnyScopes 하나 이상 필요한 스코프 (OR 조건)
 */
private data class SlackToolScopeSpec(
    val name: String,
    val requiredScopes: Set<String> = emptySet(),
    val requiredAnyScopes: Set<String> = emptySet()
)

/**
 * 대화 스코프 모드에 따라 채널 읽기에 필요한 스코프 집합을 반환한다.
 *
 * [ConversationScopeMode.PUBLIC_ONLY]이면 공개 채널만,
 * [ConversationScopeMode.INCLUDE_PRIVATE_AND_DM]이면 비공개/DM 포함.
 */
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

/**
 * 대화 스코프 모드에 따라 메시지 히스토리 읽기에 필요한 스코프 집합을 반환한다.
 */
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

/** 도구 타입별 스코프 스펙 매핑을 생성한다. */
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

/**
 * Slack Bot 토큰에 부여된 OAuth 스코프를 기반으로 도구를 필터링하는 [LocalToolFilter].
 *
 * 도구별 필수 스코프([SlackToolScopeSpec])를 [ToolExposureResolver]로 검증하여,
 * 스코프가 충족되는 도구만 에이전트에 노출한다.
 * 스코프 스펙이 등록되지 않은 도구(커스텀 도구 등)는 무조건 통과시킨다.
 *
 * @param properties Slack 도구 설정 프로퍼티
 * @param toolExposureResolver 스코프 기반 도구 노출 결정기
 */
private class SlackScopeAwareLocalToolFilter(
    private val properties: SlackToolsProperties,
    private val toolExposureResolver: ToolExposureResolver
) : LocalToolFilter {
    private val scopeSpecsByType = slackToolScopeSpecsByType(properties.toolExposure.conversationScopeMode)

    /** 스코프가 충족되어 노출 가능한 도구 이름 집합 (지연 초기화). */
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

    /**
     * 도구 목록에서 스코프가 충족되지 않는 도구를 제거한다.
     *
     * 스코프 스펙이 없는 도구는 필터링하지 않고 통과시킨다.
     */
    override fun filter(tools: List<LocalTool>): List<LocalTool> {
        if (tools.isEmpty()) return tools
        return tools.filter { tool ->
            val spec = scopeSpecsByType[tool::class]
            spec == null || spec.name in exposedToolNames
        }
    }
}

/**
 * [SlackScopeAwareLocalToolFilter] 인스턴스를 생성하는 팩토리 함수.
 *
 * @param properties Slack 도구 설정 프로퍼티
 * @param toolExposureResolver 스코프 기반 도구 노출 결정기
 * @return 스코프 인식 도구 필터
 */
internal fun createSlackScopeAwareLocalToolFilter(
    properties: SlackToolsProperties,
    toolExposureResolver: ToolExposureResolver
): LocalToolFilter = SlackScopeAwareLocalToolFilter(properties, toolExposureResolver)
