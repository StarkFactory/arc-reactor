package com.arc.reactor.tool

import com.arc.reactor.agent.config.ToolRoutingConfig

/**
 * 도구 선택 전략 인터페이스
 *
 * 사용자 요청에 따라 적절한 도구를 필터링하고 선택한다.
 * 컨텍스트 윈도우 사용을 최적화하고 도구 선택 정확도를 향상시킨다.
 *
 * ## 왜 도구 선택이 중요한가
 * - LLM은 컨텍스트 윈도우가 제한적이다
 * - 모든 도구를 전송하면 토큰 사용량과 비용이 증가한다
 * - 도구가 너무 많으면 LLM의 도구 선택이 혼란스러워진다
 * - 관련성 높은 도구가 응답 품질을 향상시킨다
 *
 * ## 사용 예시
 * ```kotlin
 * val selector = KeywordBasedToolSelector(toolCategoryMap)
 * val relevantTools = selector.select(
 *     prompt = "회사 정보를 검색해줘",
 *     availableTools = allTools
 * )
 * // SEARCH 카테고리 도구만 반환됨
 * ```
 *
 * @see KeywordBasedToolSelector 키워드 기반 필터링
 * @see AllToolSelector 필터링 없음 (패스스루)
 * @see ToolCategory 도구 카테고리 정의
 */
interface ToolSelector {
    /**
     * 사용자 프롬프트에 따라 관련 도구를 선택한다.
     *
     * @param prompt 사용자의 요청 텍스트
     * @param availableTools 등록된 전체 도구 목록
     * @return 프롬프트와 관련된 도구만 포함하는 필터링된 목록
     */
    fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback>
}

/**
 * 키워드 기반 도구 선택기
 *
 * 사용자 프롬프트의 키워드와 도구 카테고리를 매칭한다.
 * 매칭되는 카테고리가 없으면 모든 도구를 반환한다 (안전 폴백).
 *
 * ## 매칭 로직
 * 1. 프롬프트에서 키워드가 매칭되는 카테고리를 추출한다
 * 2. 매칭된 카테고리가 있으면: 해당 카테고리 도구 + 미분류 도구를 반환
 * 3. 매칭된 카테고리가 없으면: 전체 도구를 반환 (안전 폴백)
 *
 * ## 설정 기반 생성
 * ```kotlin
 * // tool-routing.yml에서 빌드 — 각 라우트 카테고리가 ToolCategory가 된다
 * val selector = KeywordBasedToolSelector.fromRoutingConfig()
 * ```
 *
 * @param toolCategoryMap 도구 이름과 카테고리의 매핑
 */
class KeywordBasedToolSelector(
    private val toolCategoryMap: Map<String, ToolCategory> = emptyMap()
) : ToolSelector {

    override fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        if (toolCategoryMap.isEmpty()) {
            return availableTools
        }

        val matchedCategories = toolCategoryMap.values
            .filter { it.matches(prompt) }
            .toSet()

        if (matchedCategories.isEmpty()) {
            return availableTools
        }

        return availableTools.filter { callback ->
            val category = toolCategoryMap[callback.name]
            category == null || category in matchedCategories
        }
    }

    companion object {
        /**
         * tool-routing.yml 설정에서 KeywordBasedToolSelector를 생성한다.
         *
         * preferredTools가 비어있지 않은 각 라우트에 대해
         * toolCategoryMap 항목을 생성한다: 도구 이름 -> ToolCategory (라우트 카테고리 + 키워드 파생).
         *
         * @param config 라우팅 설정 (기본값: 클래스패스에서 로딩)
         * @return 설정에서 카테고리 매핑을 가진 KeywordBasedToolSelector
         */
        fun fromRoutingConfig(
            config: ToolRoutingConfig = ToolRoutingConfig.loadFromClasspath()
        ): KeywordBasedToolSelector {
            val categoryObjects = buildCategoryMap(config)
            val toolCategoryMap = mutableMapOf<String, ToolCategory>()

            for (route in config.routes) {
                if (route.preferredTools.isEmpty()) continue
                val category = categoryObjects[route.category] ?: continue
                for (toolName in route.preferredTools) {
                    toolCategoryMap.putIfAbsent(toolName, category)
                }
            }

            return KeywordBasedToolSelector(toolCategoryMap)
        }

        /**
         * 동일 카테고리를 공유하는 라우트의 모든 키워드를
         * 카테고리 이름별 단일 ToolCategory로 집계한다.
         */
        private fun buildCategoryMap(
            config: ToolRoutingConfig
        ): Map<String, ToolCategory> {
            val keywordsByCategory = mutableMapOf<String, MutableSet<String>>()
            for (route in config.routes) {
                val keywords = keywordsByCategory.getOrPut(route.category) {
                    mutableSetOf()
                }
                keywords.addAll(route.keywords)
                keywords.addAll(route.requiredKeywords)
            }

            return keywordsByCategory.map { (name, keywords) ->
                name to object : ToolCategory {
                    override val name = name
                    override val keywords = keywords.toSet()
                }
            }.toMap()
        }
    }
}

/**
 * 패스스루 도구 선택기
 *
 * 필터링 없이 모든 도구를 반환한다.
 * 도구 선택이 불필요하거나 다른 곳에서 처리될 때 사용한다.
 *
 * ## 사용 사례
 * - 모든 도구를 사용하는 개발/테스트 환경
 * - 필터링 오버헤드가 불필요한 소규모 도구 집합
 * - 에이전트 내부에서 커스텀 선택 로직을 구현하는 경우
 */
class AllToolSelector : ToolSelector {
    override fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        return availableTools
    }
}
