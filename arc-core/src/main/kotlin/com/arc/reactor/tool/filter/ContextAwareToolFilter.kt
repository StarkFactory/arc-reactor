package com.arc.reactor.tool.filter

import com.arc.reactor.tool.ToolCallback
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 도구 필터 인터페이스.
 *
 * 실행 컨텍스트(의도, 채널, 사용자 역할)에 따라
 * LLM에 제공되는 도구 목록을 동적으로 필터링한다.
 *
 * ## 사용 목적
 * - Intent 프로필의 `allowedTools`를 기반으로 도구 필터링
 * - 채널별 도구 제한 (예: Slack에서는 위험 도구 차단)
 * - 사용자 역할별 도구 접근 제어
 *
 * ## 사용 예시
 * ```kotlin
 * val filter = ContextAwareToolFilter(properties)
 * val filtered = filter.filter(allTools, ToolFilterContext(
 *     userId = "user-1",
 *     channel = "slack",
 *     intent = "order_inquiry",
 *     userRole = "user"
 * ))
 * ```
 *
 * @see ToolFilterContext 필터 컨텍스트
 * @see ToolFilterProperties 필터 설정
 */
fun interface ToolFilter {

    /**
     * 도구 목록을 실행 컨텍스트에 따라 필터링한다.
     *
     * @param tools 전체 등록된 도구 목록
     * @param context 현재 실행 컨텍스트 (사용자, 채널, 인텐트, 역할)
     * @return 컨텍스트에 적합한 도구만 포함된 필터링된 목록
     */
    fun filter(tools: List<ToolCallback>, context: ToolFilterContext): List<ToolCallback>
}

/**
 * 도구 필터링 컨텍스트.
 *
 * 필터가 결정을 내리는 데 필요한 실행 환경 정보를 담는다.
 *
 * @param userId 현재 사용자 식별자
 * @param channel 요청이 들어온 채널 (예: "web", "slack", "teams"). null이면 채널 필터 미적용.
 * @param intent 분류된 인텐트 이름 (예: "order_inquiry"). null이면 인텐트 필터 미적용.
 * @param userRole 사용자 역할 (예: "admin", "user"). null이면 역할 필터 미적용.
 */
data class ToolFilterContext(
    val userId: String,
    val channel: String? = null,
    val intent: String? = null,
    val userRole: String? = null
)

/**
 * 컨텍스트 인식 도구 필터.
 *
 * 3단계 필터링 파이프라인을 적용한다:
 * 1. **인텐트 필터**: `allowedTools`에 포함된 도구만 허용
 * 2. **채널 필터**: 특정 채널에서 제한된 도구 차단
 * 3. **역할 필터**: 특정 역할에 허용된 도구만 노출
 *
 * 각 단계는 독립적으로 적용되며, 모든 조건을 만족하는 도구만 최종 반환된다.
 * 설정이 없거나 컨텍스트 값이 null이면 해당 단계를 건너뛴다 (fail-open).
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     tool-filter:
 *       enabled: true
 *       channel-restrictions:
 *         slack:
 *           - delete_order
 *           - drop_database
 *       role-allowed-tools:
 *         admin:
 *           - "*"
 *         user:
 *           - search_order
 *           - check_status
 * ```
 *
 * @param properties 도구 필터 설정
 * @see ToolFilter 도구 필터 인터페이스
 * @see ToolFilterProperties 설정 속성
 */
class ContextAwareToolFilter(
    private val properties: ToolFilterProperties
) : ToolFilter {

    override fun filter(tools: List<ToolCallback>, context: ToolFilterContext): List<ToolCallback> {
        if (!properties.enabled) {
            return tools
        }

        var filtered = tools

        // 1단계: 인텐트 기반 필터링
        filtered = filterByIntent(filtered, context.intent)

        // 2단계: 채널 기반 필터링
        filtered = filterByChannel(filtered, context.channel)

        // 3단계: 역할 기반 필터링
        filtered = filterByRole(filtered, context.userRole)

        if (filtered.size < tools.size) {
            logger.debug {
                "ToolFilter: ${tools.size} -> ${filtered.size} 도구 (intent=${context.intent}, " +
                    "channel=${context.channel}, role=${context.userRole})"
            }
        }

        return filtered
    }

    /**
     * 인텐트의 allowedTools 설정에 따라 도구를 필터링한다.
     *
     * [intentAllowedTools]에 인텐트 이름이 등록되어 있으면,
     * 해당 인텐트에 허용된 도구만 반환한다.
     * 등록되지 않은 인텐트이거나 intent가 null이면 모든 도구를 반환한다.
     */
    private fun filterByIntent(tools: List<ToolCallback>, intent: String?): List<ToolCallback> {
        if (intent == null) return tools
        val allowed = properties.intentAllowedTools[intent] ?: return tools
        if (allowed.isEmpty()) return tools
        return tools.filter { it.name in allowed }
    }

    /**
     * 채널별 제한된 도구를 차단한다.
     *
     * [channelRestrictions]에 현재 채널이 등록되어 있으면,
     * 해당 채널에서 제한된 도구를 제외한다.
     */
    private fun filterByChannel(tools: List<ToolCallback>, channel: String?): List<ToolCallback> {
        if (channel == null) return tools
        val restricted = properties.channelRestrictions[channel] ?: return tools
        if (restricted.isEmpty()) return tools
        return tools.filter { it.name !in restricted }
    }

    /**
     * 사용자 역할에 따라 허용된 도구만 반환한다.
     *
     * [roleAllowedTools]에 현재 역할이 등록되어 있으면,
     * 해당 역할에 허용된 도구만 반환한다.
     * "*" 와일드카드는 모든 도구를 허용한다.
     * 등록되지 않은 역할이거나 userRole이 null이면 모든 도구를 반환한다.
     */
    private fun filterByRole(tools: List<ToolCallback>, userRole: String?): List<ToolCallback> {
        if (userRole == null) return tools
        val allowed = properties.roleAllowedTools[userRole] ?: return tools
        if (allowed.isEmpty()) return tools
        if (WILDCARD in allowed) return tools
        return tools.filter { it.name in allowed }
    }

    companion object {
        private const val WILDCARD = "*"
    }
}

/**
 * 도구 필터 설정.
 *
 * `arc.reactor.tool-filter.*` 접두사로 바인딩된다.
 *
 * @param enabled 도구 필터 활성화 여부. 기본 비활성 (opt-in).
 * @param channelRestrictions 채널별 제한 도구 목록. 키=채널 이름, 값=차단할 도구 이름 집합.
 * @param roleAllowedTools 역할별 허용 도구 목록. 키=역할 이름, 값=허용할 도구 이름 집합. "*"=모든 도구 허용.
 * @param intentAllowedTools 인텐트별 허용 도구 목록. 키=인텐트 이름, 값=허용할 도구 이름 집합.
 */
data class ToolFilterProperties(
    /** 도구 필터 활성화 여부. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 채널별 제한 도구 목록. 예: `slack: [delete_order, drop_database]` */
    val channelRestrictions: Map<String, Set<String>> = emptyMap(),

    /** 역할별 허용 도구 목록. 예: `admin: ["*"], user: [search, check_status]` */
    val roleAllowedTools: Map<String, Set<String>> = emptyMap(),

    /** 인텐트별 허용 도구 목록. 예: `order_inquiry: [search_order, check_status]` */
    val intentAllowedTools: Map<String, Set<String>> = emptyMap()
)

/**
 * 패스스루 도구 필터.
 *
 * 필터링을 수행하지 않고 모든 도구를 그대로 반환한다.
 * 도구 필터가 비활성화되었거나 커스텀 필터가 등록되지 않았을 때의 기본 구현.
 */
class NoOpToolFilter : ToolFilter {
    override fun filter(tools: List<ToolCallback>, context: ToolFilterContext): List<ToolCallback> = tools
}
