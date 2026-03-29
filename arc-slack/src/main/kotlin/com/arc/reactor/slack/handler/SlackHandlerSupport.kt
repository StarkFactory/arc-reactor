package com.arc.reactor.slack.handler

import com.arc.reactor.mcp.McpManager
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.slack.service.SlackUserEmailResolver
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * DefaultSlackEventHandler, DefaultSlackCommandHandler 공용 유틸리티.
 *
 * MCP 도구 요약 생성, 사용자 메모리 컨텍스트 조회, 요청자 이메일 조회 등
 * 두 핸들러에서 동일하게 사용하는 로직을 집중한다.
 */
internal object SlackHandlerSupport {

    /** 연결된 MCP 서버의 도구 요약 텍스트를 생성한다. */
    fun buildToolSummary(mcpManager: McpManager?): String? {
        val manager = mcpManager ?: return null
        val toolsByServer = manager.listServers()
            .filter { manager.getStatus(it.name)?.name == "CONNECTED" }
            .associate { server ->
                server.name to manager.getToolCallbacks(server.name).map { it.name }
            }
            .filter { it.value.isNotEmpty() }
        return SlackSystemPromptFactory.buildToolSummary(toolsByServer)
    }

    /** 사용자 장기 메모리에서 컨텍스트 프롬프트를 조회한다. */
    suspend fun resolveUserContext(
        userId: String,
        userMemoryManager: UserMemoryManager?
    ): String {
        val manager = userMemoryManager ?: return ""
        return try {
            manager.getContextPrompt(userId)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "사용자 메모리 조회 실패: userId=$userId" }
            ""
        }
    }

    /** Slack users.info로 사용자 이메일을 조회한다. */
    suspend fun resolveRequesterEmail(
        userId: String,
        userEmailResolver: SlackUserEmailResolver?
    ): String? {
        val resolver = userEmailResolver ?: return null
        return try {
            resolver.resolveEmail(userId)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Slack 요청자 이메일 조회 실패: userId=$userId" }
            null
        }
    }
}
