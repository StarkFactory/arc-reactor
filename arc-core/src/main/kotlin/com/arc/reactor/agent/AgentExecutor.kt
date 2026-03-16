package com.arc.reactor.agent

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * AI 에이전트 실행기 인터페이스.
 *
 * ReAct(Reasoning + Acting) 패턴을 사용하여 AI 에이전트를 실행한다.
 * 에이전트는 작업에 대해 자율적으로 추론하고, 도구를 선택 및 실행하며,
 * 결과를 관찰하고, 작업이 완료될 때까지 반복한다.
 *
 * ## ReAct 루프
 * ```
 * 목표 → [사고] → [행동] → [관찰] → ... → 최종 답변
 * ```
 *
 * ## 실행 흐름
 * 1. Guard 파이프라인 — 보안 검사 (속도 제한, 인젝션 탐지 등)
 * 2. BeforeAgentStart 훅 — 전처리 및 검증
 * 3. 에이전트 루프 — 도구 실행을 포함한 LLM 추론
 * 4. AfterAgentComplete 훅 — 후처리 및 감사
 *
 * ## 사용 예시
 * ```kotlin
 * @Service
 * class MyService(private val agentExecutor: AgentExecutor) {
 *
 *     suspend fun chat(message: String): String {
 *         val result = agentExecutor.execute(
 *             AgentCommand(
 *                 systemPrompt = "You are a helpful assistant.",
 *                 userPrompt = message,
 *                 userId = "user-123"
 *             )
 *         )
 *         return result.content ?: "Error: ${result.errorMessage}"
 *     }
 * }
 * ```
 *
 * @see AgentCommand 입력 파라미터
 * @see AgentResult 출력 구조
 * @see com.arc.reactor.agent.impl.SpringAiAgentExecutor 기본 구현체
 */
interface AgentExecutor {

    /**
     * 주어진 명령으로 에이전트를 실행한다.
     *
     * 다음을 포함하는 전체 에이전트 실행 파이프라인을 조율한다:
     * - Guard 검증 (userId가 제공된 경우)
     * - 훅 실행 (전/후)
     * - 도구 호출을 포함한 LLM 상호작용
     * - 대화 메모리 관리
     *
     * @param command 프롬프트, 모드, 설정을 포함하는 에이전트 명령
     * @return 성공 상태, 응답 내용, 메타데이터를 포함하는 AgentResult
     *
     * @throws Nothing 모든 예외는 AgentResult.errorMessage에 담겨 반환됨
     */
    suspend fun execute(command: AgentCommand): AgentResult

    /**
     * 시스템 프롬프트와 사용자 프롬프트만으로 실행하는 간소화 메서드.
     *
     * 고급 설정 없이 간단한 사용 사례를 위한 편의 메서드.
     *
     * @param systemPrompt 에이전트 동작을 정의하는 시스템 프롬프트
     * @param userPrompt 사용자의 입력 메시지
     * @return 에이전트 응답이 담긴 AgentResult
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

    /**
     * 스트리밍 모드로 에이전트를 실행하여 청크를 Flow로 반환한다.
     *
     * [execute]와 동일한 Guard 및 훅 파이프라인을 실행하되,
     * LLM 응답을 Kotlin Flow를 통해 토큰 단위로 스트리밍한다.
     *
     * Guard가 거부하거나 훅이 차단하면 단일 에러 청크가 발행된다.
     *
     * @param command 에이전트 명령
     * @return 응답 텍스트 청크의 Flow
     */
    fun executeStream(command: AgentCommand): Flow<String> = flowOf()
}
