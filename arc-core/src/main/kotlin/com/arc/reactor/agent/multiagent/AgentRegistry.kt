package com.arc.reactor.agent.multiagent

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 전문 에이전트 레지스트리 인터페이스.
 *
 * 전문 에이전트([AgentSpec])를 등록, 해제, 조회하는 기능을 제공한다.
 * [SupervisorAgent]가 사용자 쿼리에 적합한 에이전트를 찾을 때 이 레지스트리를 참조한다.
 *
 * ## 사용 예시
 * ```kotlin
 * registry.register(jiraAgentSpec)
 * registry.register(confluenceAgentSpec)
 *
 * val agent = registry.findById("jira-agent")
 * val matched = registry.findByCapability("Jira 이슈 검색해줘")
 * ```
 *
 * @see AgentSpec 전문 에이전트 사양
 * @see DefaultAgentRegistry 인메모리 기본 구현
 * @see SupervisorAgent 이 레지스트리를 사용하여 위임 대상 결정
 */
interface AgentRegistry {

    /**
     * 전문 에이전트를 등록한다.
     *
     * 동일한 ID의 에이전트가 이미 등록되어 있으면 덮어쓴다.
     *
     * @param spec 등록할 에이전트 사양
     */
    fun register(spec: AgentSpec)

    /**
     * 등록된 에이전트를 해제한다.
     *
     * @param id 해제할 에이전트 ID
     * @return 해제 성공 여부 (미등록이면 false)
     */
    fun unregister(id: String): Boolean

    /**
     * ID로 에이전트를 조회한다.
     *
     * @param id 에이전트 ID
     * @return 해당 에이전트 사양 (미등록이면 null)
     */
    fun findById(id: String): AgentSpec?

    /**
     * 등록된 모든 에이전트를 반환한다.
     *
     * @return 등록된 에이전트 사양 목록
     */
    fun findAll(): List<AgentSpec>

    /**
     * 사용자 쿼리와 매칭되는 에이전트를 검색한다.
     *
     * 에이전트의 키워드, 설명, 도구 이름을 기준으로 매칭한다.
     * 매칭 점수가 높은 순서로 정렬하여 반환한다.
     *
     * @param query 사용자 쿼리
     * @return 매칭된 에이전트 사양 목록 (매칭 점수 내림차순)
     */
    fun findByCapability(query: String): List<AgentSpec>
}

/**
 * 인메모리 기반 기본 에이전트 레지스트리.
 *
 * [ConcurrentHashMap]을 사용하여 스레드 안전하게 에이전트를 관리한다.
 * 키워드 매칭은 대소문자 무시로 수행한다.
 *
 * @see AgentRegistry 인터페이스 정의
 */
class DefaultAgentRegistry : AgentRegistry {

    private val agents = ConcurrentHashMap<String, AgentSpec>()

    override fun register(spec: AgentSpec) {
        agents[spec.id] = spec
        logger.debug { "에이전트 등록: id=${spec.id}, name=${spec.name}" }
    }

    override fun unregister(id: String): Boolean {
        val removed = agents.remove(id) != null
        if (removed) {
            logger.debug { "에이전트 해제: id=$id" }
        }
        return removed
    }

    override fun findById(id: String): AgentSpec? = agents[id]

    override fun findAll(): List<AgentSpec> = agents.values.toList()

    override fun findByCapability(query: String): List<AgentSpec> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return agents.values
            .map { spec -> spec to calculateMatchScore(spec, lowerQuery) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (spec, _) -> spec }
    }

    companion object {
        /** 키워드 매칭 점수 가중치 */
        internal const val KEYWORD_WEIGHT = 3

        /** 도구 이름 매칭 점수 가중치 */
        internal const val TOOL_NAME_WEIGHT = 2

        /** 설명 매칭 점수 가중치 */
        internal const val DESCRIPTION_WEIGHT = 1

        /**
         * 에이전트 사양과 쿼리 간 매칭 점수를 계산한다.
         *
         * 키워드 > 도구 이름 > 설명 순으로 가중치를 부여한다.
         */
        internal fun calculateMatchScore(
            spec: AgentSpec,
            lowerQuery: String
        ): Int {
            var score = 0
            for (keyword in spec.keywords) {
                if (lowerQuery.contains(keyword.lowercase())) {
                    score += KEYWORD_WEIGHT
                }
            }
            for (toolName in spec.toolNames) {
                if (lowerQuery.contains(toolName.lowercase())) {
                    score += TOOL_NAME_WEIGHT
                }
            }
            val lowerDesc = spec.description.lowercase()
            if (lowerDesc.isNotBlank() && hasCommonTerms(lowerQuery, lowerDesc)) {
                score += DESCRIPTION_WEIGHT
            }
            return score
        }

        /**
         * 쿼리와 설명 사이에 공통 단어(3자 이상)가 있는지 확인한다.
         */
        private fun hasCommonTerms(query: String, description: String): Boolean {
            val queryTerms = query.split(WORD_SPLIT_PATTERN)
                .filter { it.length >= MIN_TERM_LENGTH }
                .toSet()
            return description.split(WORD_SPLIT_PATTERN)
                .filter { it.length >= MIN_TERM_LENGTH }
                .any { it in queryTerms }
        }

        private val WORD_SPLIT_PATTERN = Regex("[\\s,.:;!?]+")

        /** 공통 단어 매칭을 위한 최소 단어 길이 */
        private const val MIN_TERM_LENGTH = 3
    }
}
