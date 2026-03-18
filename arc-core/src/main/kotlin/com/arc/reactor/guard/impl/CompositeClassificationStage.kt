package com.arc.reactor.guard.impl

import com.arc.reactor.guard.ClassificationStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 복합 분류 단계 (4단계)
 *
 * 규칙 기반 분류를 먼저 실행하고, 통과한 경우에만 LLM 기반 분류를 폴백으로 실행한다.
 * [com.arc.reactor.agent.intent.CompositeIntentClassifier]와 동일한 패턴을 따른다.
 *
 * ## 실행 전략: 규칙 우선, LLM 폴백
 * ```
 * 입력 → [규칙 기반 검사] → 거부? → 즉시 반환
 *                          → 통과? → [LLM 검사 (활성화된 경우)] → 최종 결과
 * ```
 *
 * 왜 이 순서인가:
 * 1. 규칙 기반 검사는 LLM 비용이 0이므로 항상 먼저 실행한다
 * 2. 규칙으로 명확히 차단 가능한 요청은 LLM을 호출할 필요가 없다
 * 3. LLM은 규칙으로 잡지 못하는 미묘한 경우에만 방어-심층(defense-in-depth)으로 사용한다
 *
 * @param ruleBasedStage 규칙 기반 분류 단계 (필수)
 * @param llmStage LLM 기반 분류 단계 (선택사항, null이면 규칙 기반만 사용)
 *
 * @see RuleBasedClassificationStage 규칙 기반 분류 구현체
 * @see LlmClassificationStage LLM 기반 분류 구현체
 * @see com.arc.reactor.guard.ClassificationStage 분류 단계 인터페이스
 */
class CompositeClassificationStage(
    private val ruleBasedStage: RuleBasedClassificationStage,
    private val llmStage: LlmClassificationStage? = null
) : ClassificationStage {

    override val stageName = "Classification"

    override suspend fun enforce(command: GuardCommand): GuardResult {
        // ── 단계 1: 규칙 기반 검사 (비용 0) ──
        val ruleResult = ruleBasedStage.enforce(command)

        if (ruleResult is GuardResult.Rejected) {
            logger.debug { "Rule-based classification rejected: ${ruleResult.reason}" }
            return ruleResult
        }

        // ── 단계 2: LLM 폴백 (규칙 통과 + LLM 활성화 시) ──
        if (llmStage != null) {
            logger.debug { "Rule-based passed, trying LLM classification fallback" }
            return llmStage.enforce(command)
        }

        return ruleResult
    }
}
