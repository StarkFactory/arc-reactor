package com.arc.reactor.promptlab.model

/**
 * Evaluation tier for the 3-tier prompt evaluation pipeline.
 *
 * Tiers are executed sequentially with fail-fast: if a lower tier fails,
 * higher tiers are skipped.
 */
enum class EvaluationTier {
    /** Tier 1: JSON structure + required field validation (FREE, instant) */
    STRUCTURAL,

    /** Tier 2: Deterministic rule-based evaluation (FREE, instant) */
    RULES,

    /** Tier 3: LLM semantic judgment (PAID, slow) */
    LLM_JUDGE
}
