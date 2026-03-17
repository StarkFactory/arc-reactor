package com.arc.reactor.agent.config

/**
 * ToolRoute 매칭 엔진.
 *
 * 사용자 프롬프트가 주어진 [ToolRoute]의 조건을 충족하는지 평가한다.
 * SystemPromptBuilder와 ToolSelector가 동일한 매칭 로직을 공유하기 위해
 * 별도 유틸리티로 분리되었다.
 */
object ToolRouteMatchEngine {

    private val OPENAPI_URL_REGEX by lazy { ToolRoutingConfig.resolveRegex("OPENAPI_URL") }

    /**
     * 프롬프트가 주어진 라우트의 매칭 조건을 만족하는지 평가한다.
     *
     * @param route 평가할 라우트 규칙
     * @param prompt 사용자 프롬프트 (원문)
     * @param config 전체 라우팅 설정 (parentRoute 해석에 필요)
     * @param alreadyMatchedRouteIds 이미 매칭된 라우트 id 목록 (excludeRoutes 평가용)
     * @return 매칭 성공 시 true
     */
    fun matches(
        route: ToolRoute,
        prompt: String,
        config: ToolRoutingConfig,
        alreadyMatchedRouteIds: Set<String> = emptySet()
    ): Boolean {
        val normalized = prompt.lowercase()

        if (!matchesExcludeRoutes(route, alreadyMatchedRouteIds)) return false
        if (!matchesExcludeKeywords(route, normalized)) return false
        if (!matchesRegex(route, prompt)) return false
        if (!matchesKeywords(route, normalized, config)) return false
        if (!matchesRequiredKeywords(route, normalized)) return false
        if (!matchesMultiKeywordGroups(route, normalized)) return false
        if (!matchesUrlConstraint(route, prompt)) return false

        return true
    }

    private fun matchesExcludeRoutes(
        route: ToolRoute,
        alreadyMatchedRouteIds: Set<String>
    ): Boolean {
        if (route.excludeRoutes.isEmpty()) return true
        return route.excludeRoutes.none { it in alreadyMatchedRouteIds }
    }

    private fun matchesExcludeKeywords(route: ToolRoute, normalized: String): Boolean {
        if (route.excludeKeywords.isEmpty()) return true
        return route.excludeKeywords.none { normalized.contains(it) }
    }

    private fun matchesRegex(route: ToolRoute, prompt: String): Boolean {
        val ref = route.regexPatternRef ?: return true
        return ToolRoutingConfig.resolveRegex(ref).containsMatchIn(prompt.uppercase())
    }

    private fun matchesKeywords(
        route: ToolRoute,
        normalized: String,
        config: ToolRoutingConfig
    ): Boolean {
        val parentId = route.parentRoute
        if (parentId != null) {
            val parent = config.routesById[parentId] ?: return false
            return parent.keywords.any { normalized.contains(it) }
        }
        if (route.keywords.isEmpty() && route.multiKeywordGroups.isEmpty()) {
            return route.regexPatternRef != null
        }
        return route.keywords.any { normalized.contains(it) }
    }

    private fun matchesRequiredKeywords(route: ToolRoute, normalized: String): Boolean {
        if (route.requiredKeywords.isEmpty()) return true
        return route.requiredKeywords.any { normalized.contains(it) }
    }

    private fun matchesMultiKeywordGroups(route: ToolRoute, normalized: String): Boolean {
        if (route.multiKeywordGroups.isEmpty()) return true
        return route.multiKeywordGroups.all { group ->
            group.any { normalized.contains(it) }
        }
    }

    private fun matchesUrlConstraint(route: ToolRoute, prompt: String): Boolean {
        if (!route.requiresNoUrl) return true
        return !OPENAPI_URL_REGEX.containsMatchIn(prompt)
    }

    /**
     * 주어진 카테고리의 라우트 목록에서 프롬프트와 매칭되는 첫 번째 라우트를 반환한다.
     * priority 순서대로 평가하며 첫 번째 매칭에서 중단한다.
     *
     * @param category 카테고리 이름
     * @param prompt 사용자 프롬프트
     * @param config 전체 라우팅 설정
     * @return 매칭된 라우트 (없으면 null)
     */
    fun findFirstMatch(
        category: String,
        prompt: String,
        config: ToolRoutingConfig
    ): ToolRoute? {
        val routes = config.routesByCategory[category] ?: return null
        val matchedIds = mutableSetOf<String>()
        for (route in routes) {
            if (matches(route, prompt, config, matchedIds)) {
                matchedIds.add(route.id)
                return route
            }
        }
        return null
    }

    /**
     * 주어진 카테고리에서 프롬프트와 매칭되는 모든 라우트를 반환한다.
     * priority 순서대로 반환한다.
     *
     * @param category 카테고리 이름
     * @param prompt 사용자 프롬프트
     * @param config 전체 라우팅 설정
     * @return 매칭된 라우트 목록
     */
    fun findAllMatches(
        category: String,
        prompt: String,
        config: ToolRoutingConfig
    ): List<ToolRoute> {
        val routes = config.routesByCategory[category] ?: return emptyList()
        val matchedIds = mutableSetOf<String>()
        val result = mutableListOf<ToolRoute>()
        for (route in routes) {
            if (matches(route, prompt, config, matchedIds)) {
                matchedIds.add(route.id)
                result.add(route)
            }
        }
        return result
    }
}
