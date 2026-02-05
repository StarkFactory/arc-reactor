package com.arc.reactor.agent

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult

/**
 * AI Agent 실행기 인터페이스
 *
 * ReAct 패턴 기반 Agent Loop 실행.
 *
 * ```
 * Goal → [Thought] → [Action] → [Observation] → ... → Final Answer
 * ```
 */
interface AgentExecutor {

    /**
     * Agent 실행
     *
     * @param command 실행 명령
     * @return 실행 결과
     */
    suspend fun execute(command: AgentCommand): AgentResult

    /**
     * 간단한 실행 (시스템 프롬프트 + 사용자 프롬프트)
     */
    suspend fun execute(
        systemPrompt: String,
        userPrompt: String
    ): AgentResult = execute(
        AgentCommand(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt
        )
    )
}
