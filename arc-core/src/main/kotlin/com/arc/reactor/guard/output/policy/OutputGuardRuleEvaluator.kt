package com.arc.reactor.guard.output.policy

import java.util.concurrent.ConcurrentHashMap

/**
 * 출력 Guard 규칙 평가 엔진
 *
 * 정규식 기반 출력 Guard 정책을 평가하는 공유 엔진이다.
 * [com.arc.reactor.guard.output.impl.DynamicRuleOutputGuard]가 이 엔진을 사용하여
 * 동적 규칙을 콘텐츠에 적용한다.
 *
 * ## 정규식 캐시
 * 패턴 문자열을 키로 컴파일된 Regex를 캐시한다.
 * 잘못된 패턴은 별도의 집합에 기록하여 반복적인 컴파일 실패를 방지한다.
 *
 * ## 평가 순서
 * 규칙은 전달된 순서대로 평가된다 (호출자가 priority 정렬 담당).
 * REJECT 규칙이 매칭되면 즉시 평가를 중단하고 결과를 반환한다.
 * MASK 규칙이 매칭되면 해당 부분을 규칙의 replacement 문자열로 치환하고 계속 진행한다.
 *
 * @see com.arc.reactor.guard.output.impl.DynamicRuleOutputGuard 이 엔진을 사용하는 Guard
 */
class OutputGuardRuleEvaluator {

    /** 패턴 문자열 → 컴파일된 Regex 캐시 */
    private val regexCache = ConcurrentHashMap<String, Regex>()

    /** 컴파일에 실패한 잘못된 패턴 문자열 집합 */
    private val invalidPatterns: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 콘텐츠에 대해 규칙 목록을 평가한다.
     *
     * @param content 검사할 LLM 응답 콘텐츠
     * @param rules 평가할 규칙 목록 (priority 정렬 상태여야 함)
     * @return 평가 결과 (차단 여부, 수정된 콘텐츠, 매칭된 규칙, 잘못된 규칙)
     */
    fun evaluate(content: String, rules: List<OutputGuardRule>): OutputGuardEvaluation {
        if (rules.isEmpty()) return OutputGuardEvaluation.allowed(content)

        var maskedContent = content
        val matched = mutableListOf<OutputGuardRuleMatch>()
        val invalid = mutableListOf<InvalidOutputGuardRule>()

        for (rule in rules) {
            // ── 잘못된 패턴 건너뛰기 ──
            // 이전에 컴파일 실패한 패턴은 재시도하지 않음
            if (rule.pattern in invalidPatterns) {
                invalid.add(
                    InvalidOutputGuardRule(
                        ruleId = rule.id,
                        ruleName = rule.name,
                        reason = "invalid regex"
                    )
                )
                continue
            }

            // ── 정규식 컴파일 (캐시 활용) ──
            val regex = regexCache.getOrPut(rule.pattern) {
                runCatching { Regex(rule.pattern) }.getOrElse {
                    invalidPatterns.add(rule.pattern)
                    invalid.add(
                        InvalidOutputGuardRule(
                            ruleId = rule.id,
                            ruleName = rule.name,
                            reason = it.message ?: "invalid regex"
                        )
                    )
                    continue
                }
            }

            // ── 매칭 검사 ──
            if (!regex.containsMatchIn(maskedContent)) continue
            val ruleMatch = OutputGuardRuleMatch(
                ruleId = rule.id,
                ruleName = rule.name,
                action = rule.action,
                priority = rule.priority
            )
            matched.add(ruleMatch)

            when (rule.action) {
                OutputGuardRuleAction.REJECT -> {
                    // REJECT: 즉시 평가 중단하고 차단 결과 반환
                    return OutputGuardEvaluation(
                        blocked = true,
                        content = maskedContent,
                        matchedRules = matched.toList(),
                        blockedBy = ruleMatch,
                        invalidRules = invalid.toList()
                    )
                }

                OutputGuardRuleAction.MASK -> {
                    // MASK: 매칭된 부분을 규칙의 replacement 문자열로 치환하고 계속
                    maskedContent = regex.replace(maskedContent, rule.replacement)
                }
            }
        }

        return OutputGuardEvaluation(
            blocked = false,
            content = maskedContent,
            matchedRules = matched.toList(),
            blockedBy = null,
            invalidRules = invalid.toList()
        )
    }
}

/**
 * 매칭된 규칙 정보
 *
 * @property ruleId 규칙 ID
 * @property ruleName 규칙 이름
 * @property action 적용된 동작 (MASK 또는 REJECT)
 * @property priority 규칙 우선순위
 */
data class OutputGuardRuleMatch(
    val ruleId: String,
    val ruleName: String,
    val action: OutputGuardRuleAction,
    val priority: Int
)

/**
 * 잘못된 규칙 정보 (정규식 컴파일 실패)
 *
 * @property ruleId 규칙 ID
 * @property ruleName 규칙 이름
 * @property reason 실패 사유
 */
data class InvalidOutputGuardRule(
    val ruleId: String,
    val ruleName: String,
    val reason: String
)

/**
 * 출력 Guard 규칙 평가 결과
 *
 * @property blocked REJECT 규칙에 의해 차단되었는지 여부
 * @property content 최종 콘텐츠 (MASK 적용 후)
 * @property matchedRules 매칭된 규칙 목록
 * @property blockedBy 차단을 발생시킨 규칙 (차단되지 않았으면 null)
 * @property invalidRules 잘못된 정규식을 가진 규칙 목록
 */
data class OutputGuardEvaluation(
    val blocked: Boolean,
    val content: String,
    val matchedRules: List<OutputGuardRuleMatch>,
    val blockedBy: OutputGuardRuleMatch?,
    val invalidRules: List<InvalidOutputGuardRule>
) {
    /** MASK 규칙에 의해 콘텐츠가 수정되었는지 여부 */
    val modified: Boolean get() = !blocked && matchedRules.any { it.action == OutputGuardRuleAction.MASK }

    companion object {
        /** 규칙 매칭 없이 콘텐츠를 그대로 통과시키는 결과를 생성한다 */
        fun allowed(content: String): OutputGuardEvaluation = OutputGuardEvaluation(
            blocked = false,
            content = content,
            matchedRules = emptyList(),
            blockedBy = null,
            invalidRules = emptyList()
        )
    }
}
