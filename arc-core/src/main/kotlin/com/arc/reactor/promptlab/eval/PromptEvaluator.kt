package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.TestQuery

/**
 * 프롬프트 평가기 인터페이스.
 *
 * 에이전트 응답을 테스트 쿼리에 대해 평가하여 품질 점수를 산출한다.
 * 3계층 평가 파이프라인의 각 계층이 이 인터페이스를 구현한다.
 *
 * WHY: 평가 로직을 인터페이스로 추상화하여 계층별 평가기를 독립적으로
 * 개발/테스트하고, 파이프라인에 유연하게 조합할 수 있게 한다.
 *
 * @see StructuralEvaluator 1계층: 구조 검증
 * @see RuleBasedEvaluator 2계층: 규칙 기반 평가
 * @see LlmJudgeEvaluator 3계층: LLM 심판 평가
 * @see EvaluationPipeline 3계층 파이프라인 조율
 */
interface PromptEvaluator {
    /**
     * 응답을 평가하여 결과를 반환한다.
     *
     * @param response 에이전트의 응답 텍스트
     * @param query 평가 대상 테스트 쿼리
     * @return 평가 결과 (합격 여부, 점수, 사유)
     */
    suspend fun evaluate(response: String, query: TestQuery): EvaluationResult
}
