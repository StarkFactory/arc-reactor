package com.arc.reactor.autoconfigure

import com.arc.reactor.mcp.McpManager
import com.arc.reactor.tool.SemanticToolSelector
import com.arc.reactor.tool.ToolSelector
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * 애플리케이션 시작 시 MCP 서버를 초기화한다.
 *
 * MCP 서버 등록은 REST API를 통해 관리되며
 * [com.arc.reactor.mcp.McpServerStore]에 영속된다. 시작 시 이 초기화기가
 * 저장소에 이미 있는 서버를 복원하고 자동 연결한다.
 */
class McpStartupInitializer(
    private val mcpManager: McpManager,
    private val toolSelector: ToolSelector? = null
) {

    @EventListener(ApplicationReadyEvent::class)
    fun initialize() {
        runBlocking { mcpManager.initializeFromStore() }
        prewarmSemanticToolSelector()
    }

    private fun prewarmSemanticToolSelector() {
        val semanticSelector = toolSelector as? SemanticToolSelector ?: return
        semanticSelector.prewarm(mcpManager.getAllToolCallbacks())
    }
}
