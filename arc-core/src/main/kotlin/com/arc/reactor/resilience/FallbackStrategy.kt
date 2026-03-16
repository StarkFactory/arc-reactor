package com.arc.reactor.resilience

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult

/**
 * 에이전트 실행 실패 시 대체 접근법(예: 다른 LLM 모델로 폴백)으로
 * 복구를 시도하는 전략.
 *
 * 구현체는 어떤 오류를 처리할지와 어떻게 복구할지를 결정한다.
 * `null`을 반환하면 이 전략이 해당 실패를 처리할 수 없음을 의미한다.
 *
 * ## 사용 예시
 * ```kotlin
 * val strategy = ModelFallbackStrategy(
 *     fallbackModels = listOf("openai", "anthropic"),
 *     chatModelProvider = provider
 * )
 * val result = strategy.execute(command, originalError)
 * ```
 *
 * @see com.arc.reactor.resilience.impl.ModelFallbackStrategy 모델 폴백 구현체
 * @see com.arc.reactor.resilience.impl.NoOpFallbackStrategy No-op 구현체
 */
interface FallbackStrategy {

    /**
     * 실패한 에이전트 실행에서 복구를 시도한다.
     *
     * @param command 실패한 원본 에이전트 커맨드
     * @param originalError 실패를 유발한 예외
     * @return 복구에 성공하면 [AgentResult], 복구 불가하면 `null`
     */
    suspend fun execute(command: AgentCommand, originalError: Exception): AgentResult?
}
