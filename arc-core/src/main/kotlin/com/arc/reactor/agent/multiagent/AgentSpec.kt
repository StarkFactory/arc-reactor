package com.arc.reactor.agent.multiagent

import com.arc.reactor.agent.model.AgentMode

/**
 * 전문 에이전트 사양 정의.
 *
 * 각 전문 에이전트의 역할, 도구, 실행 모드를 선언적으로 정의한다.
 * [AgentRegistry]에 등록되어 [SupervisorAgent]가 쿼리를 분석하고
 * 적절한 전문 에이전트에 위임할 때 참조된다.
 *
 * ## 사용 예시
 * ```kotlin
 * val jiraSpec = AgentSpec(
 *     id = "jira-agent",
 *     name = "Jira 전문 에이전트",
 *     description = "Jira 이슈 조회, 생성, 업데이트를 담당한다",
 *     toolNames = listOf("jira_search", "jira_create_issue"),
 *     keywords = listOf("jira", "이슈", "티켓", "스프린트")
 * )
 * ```
 *
 * @param id 에이전트 고유 식별자 (예: "jira-agent")
 * @param name 표시 이름 (예: "Jira 전문 에이전트")
 * @param description 이 에이전트가 처리하는 업무 설명
 * @param toolNames 이 에이전트가 사용할 수 있는 도구 이름 목록
 * @param keywords 이 에이전트와 매칭되는 키워드 목록 (라우팅 휴리스틱용)
 * @param systemPromptOverride 커스텀 시스템 프롬프트 (null이면 기본 프롬프트 사용)
 * @param mode 실행 모드 (기본: REACT)
 *
 * @see AgentRegistry 에이전트 등록 및 조회
 * @see SupervisorAgent 쿼리 분석 및 위임
 */
data class AgentSpec(
    val id: String,
    val name: String,
    val description: String,
    val toolNames: List<String>,
    val keywords: List<String> = emptyList(),
    val systemPromptOverride: String? = null,
    val mode: AgentMode = AgentMode.REACT
) {
    init {
        require(id.isNotBlank()) { "AgentSpec id는 비어있을 수 없다" }
        require(name.isNotBlank()) { "AgentSpec name은 비어있을 수 없다" }
    }
}
