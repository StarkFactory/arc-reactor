package com.arc.reactor.tool

/**
 * Tool 선택 전략 인터페이스
 *
 * 사용자 요청에 따라 적절한 Tool만 선택하여 LLM에 전달.
 * Context Window 절약 및 도구 선택 정확도 향상.
 */
interface ToolSelector {
    /**
     * 프롬프트에 맞는 Tool 선택
     *
     * @param prompt 사용자 요청
     * @param availableTools 사용 가능한 모든 Tool
     * @return 선택된 Tool 목록
     */
    fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback>
}

/**
 * 키워드 기반 Tool 선택기 (기본 구현)
 *
 * Tool의 카테고리 키워드와 프롬프트를 매칭하여 선택.
 * 매칭되는 카테고리가 없으면 모든 Tool 반환.
 */
class KeywordBasedToolSelector(
    private val toolCategoryMap: Map<String, ToolCategory> = emptyMap()
) : ToolSelector {

    override fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        if (toolCategoryMap.isEmpty()) {
            return availableTools
        }

        // 프롬프트에서 매칭되는 카테고리 추출
        val matchedCategories = toolCategoryMap.values
            .filter { it.matches(prompt) }
            .toSet()

        // 매칭된 카테고리가 없으면 전체 반환
        if (matchedCategories.isEmpty()) {
            return availableTools
        }

        // 매칭된 카테고리의 Tool + 카테고리 없는 Tool
        return availableTools.filter { callback ->
            val category = toolCategoryMap[callback.name]
            category == null || category in matchedCategories
        }
    }
}

/**
 * 모든 Tool 선택 (필터링 없음)
 */
class AllToolSelector : ToolSelector {
    override fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        return availableTools
    }
}
