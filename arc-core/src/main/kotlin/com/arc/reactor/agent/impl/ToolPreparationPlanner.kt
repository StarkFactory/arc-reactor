package com.arc.reactor.agent.impl

import com.arc.reactor.mcp.McpToolAvailabilityChecker
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.LocalToolFilter
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 각 요청에 대해 사용 가능한 도구 목록을 준비하는 planner.
 *
 * 도구 준비 흐름:
 * 1. [LocalToolFilter]를 적용하여 로컬 도구 필터링
 * 2. 정적 콜백 + MCP 동적 콜백을 병합하고 이름 기준 중복 제거
 * 3. [ToolSelector]로 프롬프트 기반 도구 선택 (설정된 경우)
 * 4. [ArcToolCallbackAdapter]로 래핑 (캐시 사용)
 * 5. 로컬 도구 + 래핑된 콜백을 합쳐 maxToolsPerRequest 제한 적용
 *
 * @see ArcToolCallbackAdapter ToolCallback → Spring AI ToolCallback 어댑터
 * @see ToolSelector 프롬프트 기반 도구 선택기 (선택 사항)
 * @see LocalToolFilter 로컬 도구 필터 체인
 * @see SpringAiAgentExecutor ReAct 루프에서 매 반복마다 도구 목록 준비에 사용
 */
internal class ToolPreparationPlanner(
    private val localTools: List<LocalTool>,
    private val toolCallbacks: List<ToolCallback>,
    private val mcpToolCallbacks: () -> List<ToolCallback>,
    private val toolSelector: ToolSelector?,
    private val maxToolsPerRequest: Int,
    private val fallbackToolTimeoutMs: Long,
    private val localToolFilters: List<LocalToolFilter> = emptyList(),
    private val mcpToolAvailabilityChecker: McpToolAvailabilityChecker? = null
) {
    /** ToolCallback → ArcToolCallbackAdapter 캐시 (weakKeys로 GC 시 자동 제거, lock-free) */
    private val callbackAdapterCache: Cache<ToolCallback, ArcToolCallbackAdapter> =
        Caffeine.newBuilder()
            .weakKeys()
            .maximumSize(500)
            .build()

    /**
     * 사용자 프롬프트에 맞는 도구 목록을 준비한다.
     *
     * @param userPrompt 사용자 프롬프트 (ToolSelector에 전달)
     * @return LLM에 제공할 도구 목록 (최대 maxToolsPerRequest개)
     */
    fun prepareForPrompt(userPrompt: String): List<Any> {
        // 필터가 없으면 방어적 복사 없이 원본 리스트를 직접 사용 (매 요청 할당 방지)
        val localToolInstances = if (localToolFilters.isEmpty()) {
            localTools
        } else {
            localToolFilters.fold(localTools.toList()) { acc, filter ->
                runCatching { filter.filter(acc) }
                    .getOrElse { ex ->
                        logger.warn(ex) { "LocalToolFilter failed; keeping previously resolved tool list" }
                        acc
                    }
            }
        }
        val allCallbacks = deduplicateCallbacks(toolCallbacks + mcpToolCallbacks())
        val selectedCallbacks = if (toolSelector != null && allCallbacks.isNotEmpty()) {
            toolSelector.select(userPrompt, allCallbacks)
        } else {
            allCallbacks
        }
        val availableCallbacks = filterUnavailableTools(selectedCallbacks)
        val wrappedCallbacks = availableCallbacks.map(::resolveAdapter)
        val combined = localToolInstances + wrappedCallbacks
        if (combined.size > maxToolsPerRequest) {
            logger.warn {
                val dropped = combined.drop(maxToolsPerRequest)
                    .filterIsInstance<ArcToolCallbackAdapter>()
                    .map { it.arcCallback.name }
                "maxToolsPerRequest ($maxToolsPerRequest) exceeded; " +
                    "dropped ${dropped.size} callback tools: $dropped"
            }
        }
        return combined.take(maxToolsPerRequest)
    }

    /** MCP 도구 가용성 검사기를 통해 불가용 도구를 제외한다. 검사기가 없으면 원본을 그대로 반환한다. */
    private fun filterUnavailableTools(callbacks: List<ToolCallback>): List<ToolCallback> {
        val checker = mcpToolAvailabilityChecker ?: return callbacks
        if (callbacks.isEmpty()) return callbacks
        return runCatching {
            val result = checker.check(callbacks.map { it.name })
            if (result.unavailable.isEmpty()) return callbacks
            logger.warn { "MCP 불가용 도구 제외: ${result.unavailable}" }
            val unavailableSet = result.unavailable.toSet()
            callbacks.filter { it.name !in unavailableSet }
        }.getOrElse { ex ->
            logger.warn(ex) { "MCP 도구 가용성 검사 실패; 전체 도구 목록 유지" }
            callbacks
        }
    }

    /** 도구 이름 기준으로 중복을 제거한다. 같은 이름의 콜백이 여러 개면 첫 번째를 유지한다. */
    private fun deduplicateCallbacks(callbacks: List<ToolCallback>): List<ToolCallback> {
        if (callbacks.isEmpty()) return emptyList()

        val uniqueByName = LinkedHashMap<String, ToolCallback>()
        for (callback in callbacks) {
            val existing = uniqueByName.putIfAbsent(callback.name, callback)
            if (existing != null && existing !== callback) {
                logger.warn { "Duplicate tool callback name '${callback.name}' detected; keeping first callback" }
            }
        }
        return uniqueByName.values.toList()
    }

    /** ToolCallback을 ArcToolCallbackAdapter로 래핑한다. 캐시에서 먼저 조회한다. */
    private fun resolveAdapter(callback: ToolCallback): ArcToolCallbackAdapter {
        return callbackAdapterCache.get(callback) {
            ArcToolCallbackAdapter(
                arcCallback = callback,
                fallbackToolTimeoutMs = fallbackToolTimeoutMs
            )
        }
    }
}
