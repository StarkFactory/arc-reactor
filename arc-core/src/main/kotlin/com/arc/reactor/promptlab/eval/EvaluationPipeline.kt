package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationConfig
import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.TestQuery
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 3계층 평가 파이프라인을 fail-fast로 조율한다.
 *
 * 계층이 순차적으로 실행된다: 구조 -> 규칙 -> LLM 심판.
 * 한 계층이 실패하면 후속 계층은 건너뛴다.
 *
 * WHY: 저비용 검증을 먼저 실행하여 불필요한 LLM 호출을 방지한다.
 * 구조가 잘못되면 규칙 평가가 무의미하고, 규칙이 실패하면
 * 비용이 드는 LLM 심판 호출이 불필요하다.
 *
 * @param structural 1계층: 구조 평가기
 * @param rules 2계층: 규칙 기반 평가기
 * @param llmJudge 3계층: LLM 심판 평가기 (선택 — null이면 3계층 건너뜀)
 * @param config 평가 파이프라인 설정
 * @see EvaluationPipelineFactory 파이프라인 팩토리
 * @see EvaluationTier 평가 계층 정의
 */
class EvaluationPipeline(
    private val structural: StructuralEvaluator,
    private val rules: RuleBasedEvaluator,
    private val llmJudge: LlmJudgeEvaluator?,
    private val config: EvaluationConfig
) {

    /**
     * 응답을 3계층 파이프라인으로 평가한다.
     *
     * @param response 평가할 에이전트 응답
     * @param query 테스트 쿼리
     * @return 각 계층의 평가 결과 목록 (fail-fast로 실패한 계층까지만 포함)
     */
    suspend fun evaluate(
        response: String,
        query: TestQuery
    ): List<EvaluationResult> {
        val results = mutableListOf<EvaluationResult>()

        // 1계층: 구조 검증 (비용 없음, 즉시)
        if (config.structuralEnabled) {
            val result = evaluateSafe(EvaluationTier.STRUCTURAL) {
                structural.evaluate(response, query)
            }
            results.add(result)
            if (!result.passed) {
                logger.debug { "구조 계층 실패, 나머지 건너뜀" }
                return results
            }
        }

        // 2계층: 규칙 기반 (비용 없음, 즉시)
        if (config.rulesEnabled) {
            val result = evaluateSafe(EvaluationTier.RULES) {
                rules.evaluate(response, query)
            }
            results.add(result)
            if (!result.passed) {
                logger.debug { "규칙 계층 실패, LLM 심판 건너뜀" }
                return results
            }
        }

        // 3계층: LLM 심판 (유료, 느림)
        if (config.llmJudgeEnabled && llmJudge != null) {
            val result = evaluateSafe(EvaluationTier.LLM_JUDGE) {
                llmJudge.evaluate(response, query, config)
            }
            results.add(result)
        }

        return results
    }

    /**
     * 평가를 안전하게 실행한다. 예외 발생 시 실패 결과를 반환한다.
     * CancellationException은 반드시 재전파한다.
     */
    private suspend fun evaluateSafe(
        tier: EvaluationTier,
        block: suspend () -> EvaluationResult
    ): EvaluationResult {
        return try {
            block()
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "평가기 예외 발생: tier=$tier" }
            EvaluationResult(
                tier = tier,
                passed = false,
                score = 0.0,
                reason = "평가기 오류: ${e.javaClass.simpleName}"
            )
        }
    }
}

/**
 * 다양한 설정으로 EvaluationPipeline 인스턴스를 생성하는 팩토리.
 *
 * WHY: 실험마다 다른 평가 설정을 사용할 수 있어야 하므로,
 * 매 실험 시 새로운 파이프라인 인스턴스를 생성한다.
 * LLM 심판의 토큰 사용량을 실험 시작 시 리셋한다.
 *
 * @param structural 구조 평가기
 * @param rules 규칙 기반 평가기
 * @param llmJudge LLM 심판 평가기 (선택)
 */
class EvaluationPipelineFactory(
    private val structural: StructuralEvaluator,
    private val rules: RuleBasedEvaluator,
    private val llmJudge: LlmJudgeEvaluator?
) {

    /** 지정된 설정으로 새 파이프라인을 생성한다. LLM 심판의 토큰 카운터를 리셋한다. */
    fun create(config: EvaluationConfig): EvaluationPipeline {
        llmJudge?.resetTokenUsage()
        return EvaluationPipeline(structural, rules, llmJudge, config)
    }
}
