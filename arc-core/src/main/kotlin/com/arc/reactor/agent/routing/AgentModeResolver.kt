package com.arc.reactor.agent.routing

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode

/**
 * 자동 모드 선택 인터페이스.
 *
 * 사용자 쿼리와 사용 가능한 도구를 분석하여 최적의 [AgentMode]를 결정한다.
 * LLM 호출 없이 순수 휴리스틱으로 빠르게 판단한다.
 *
 * ## 사용 예시
 * ```kotlin
 * val mode = modeResolver.resolve(command, availableToolNames)
 * // mode → STANDARD, REACT, 또는 PLAN_EXECUTE
 * ```
 *
 * @see DefaultAgentModeResolver 키워드 기반 기본 구현
 * @see AgentMode 실행 모드 열거형
 */
interface AgentModeResolver {

    /**
     * 주어진 에이전트 명령과 사용 가능한 도구를 분석하여 최적의 실행 모드를 결정한다.
     *
     * @param command 분석할 에이전트 명령
     * @param availableTools 현재 사용 가능한 도구 이름 목록
     * @return 결정된 실행 모드
     */
    suspend fun resolve(
        command: AgentCommand,
        availableTools: List<String>
    ): AgentMode
}
