package com.arc.reactor.agent.config

import com.arc.reactor.agent.impl.WorkContextPatterns
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * 도구 라우팅 규칙 하나를 정의하는 데이터 클래스.
 *
 * 사용자 프롬프트가 주어졌을 때, 어떤 도구를 강제 호출할지 판단하기 위한 조건과
 * 시스템 프롬프트에 추가할 지시문 템플릿을 포함한다.
 *
 * @property id 규칙 고유 식별자 (예: "confluence_answer", "jira_search")
 * @property category 라우팅 카테고리 (confluence, work, workContext, jira, bitbucket, swagger)
 * @property keywords 프롬프트에 ANY 하나라도 포함되면 매칭 (필수)
 * @property requiredKeywords 프롬프트에 ALL 키워드 중 ANY 하나라도 포함되어야 추가 매칭
 * @property excludeKeywords 이 키워드가 포함되면 매칭 제외 (negative filter)
 * @property regexPatternRef 정규식 참조 이름 ("ISSUE_KEY", "OPENAPI_URL")
 * @property excludeRoutes 이 규칙보다 먼저 매칭되는 규칙 id 목록 (이미 매칭되면 제외)
 * @property parentRoute 부모 라우트 id (부모의 keywords도 함께 매칭해야 함)
 * @property promptInstruction 시스템 프롬프트에 추가할 도구 강제 호출 지시문
 * @property priority 같은 카테고리 내 우선순위 (낮은 숫자가 먼저 평가됨)
 * @property requiresNoUrl true이면 URL이 없을 때만 매칭
 * @property multiKeywordGroups 여러 키워드 그룹의 AND 조합이 필요한 경우 사용
 * @property preferredTools ToolSelector가 이 라우트 매칭 시 반환할 도구 이름 목록 (선택)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolRoute(
    val id: String,
    val category: String,
    val keywords: Set<String> = emptySet(),
    val requiredKeywords: Set<String> = emptySet(),
    val excludeKeywords: Set<String> = emptySet(),
    val regexPatternRef: String? = null,
    val excludeRoutes: Set<String> = emptySet(),
    val parentRoute: String? = null,
    val promptInstruction: String = "",
    val priority: Int = 100,
    val requiresNoUrl: Boolean = false,
    val multiKeywordGroups: List<Set<String>> = emptyList(),
    val preferredTools: List<String> = emptyList()
)

/**
 * 전체 도구 라우팅 설정을 담는 루트 클래스.
 *
 * YAML 파일(`tool-routing.yml`)에서 로드되며, [SystemPromptBuilder]가
 * 사용자 프롬프트를 분석하여 도구 강제 호출 지시를 생성할 때 사용한다.
 *
 * @property routes 모든 도구 라우팅 규칙 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolRoutingConfig(
    val routes: List<ToolRoute> = emptyList()
) {
    /** 카테고리별 규칙 색인 (priority 순 정렬). */
    val routesByCategory: Map<String, List<ToolRoute>> by lazy {
        routes.sortedBy { it.priority }.groupBy { it.category }
    }

    /** id별 규칙 색인. */
    val routesById: Map<String, ToolRoute> by lazy {
        routes.associateBy { it.id }
    }

    companion object {
        private val mapper = ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())

        /** YAML 파싱 결과 캐시. 설정은 정적이므로 한 번만 로드한다. */
        private val cachedConfig: ToolRoutingConfig by lazy {
            val stream = ToolRoutingConfig::class.java.classLoader
                .getResourceAsStream("tool-routing.yml")
                ?: error("tool-routing.yml not found on classpath")
            mapper.readValue(stream)
        }

        /**
         * 클래스패스의 `tool-routing.yml` 파일에서 라우팅 설정을 로드한다.
         * 결과는 캐시되어 반복 호출 시 재파싱하지 않는다.
         *
         * @return 로드된 [ToolRoutingConfig]
         * @throws IllegalStateException 파일을 찾을 수 없을 때
         */
        fun loadFromClasspath(): ToolRoutingConfig = cachedConfig

        /** 정규식 참조 이름을 실제 Regex로 변환한다. */
        fun resolveRegex(ref: String): Regex = when (ref) {
            "ISSUE_KEY" -> WorkContextPatterns.ISSUE_KEY_REGEX
            "OPENAPI_URL" -> WorkContextPatterns.OPENAPI_URL_REGEX
            else -> error("알 수 없는 정규식 참조: $ref")
        }
    }
}
