package com.arc.reactor.promptlab.model

/**
 * 3계층 프롬프트 평가 파이프라인의 평가 계층.
 *
 * 계층은 순차적으로 실행되며 fail-fast 방식이다:
 * 하위 계층이 실패하면 상위 계층은 건너뛴다.
 *
 * WHY: 저비용 검증을 먼저 수행하여 불필요한 LLM 호출 비용을 절감한다.
 *
 * @see com.arc.reactor.promptlab.eval.EvaluationPipeline 파이프라인 구현
 */
enum class EvaluationTier {
    /** 1계층: JSON 구조 + 필수 필드 검증 (무료, 즉시) */
    STRUCTURAL,

    /** 2계층: 결정적 규칙 기반 평가 (무료, 즉시) */
    RULES,

    /** 3계층: LLM 시맨틱 심판 (유료, 느림) */
    LLM_JUDGE
}
