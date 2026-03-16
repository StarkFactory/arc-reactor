package com.arc.reactor.guard.impl

import com.arc.reactor.guard.ClassificationStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 규칙 기반 분류 단계 (4단계)
 *
 * 키워드 기반으로 콘텐츠를 분류하여 알려진 유해 카테고리를 차단한다.
 * 속도가 빠르고 LLM 비용이 0인 1차 방어선이다.
 *
 * ## 동작 방식
 * 1. 입력 텍스트를 소문자로 변환한다
 * 2. 차단 대상 카테고리의 규칙들만 순회한다
 * 3. 각 규칙의 키워드 매칭 수가 [ClassificationRule.minMatchCount] 이상이면 거부한다
 *
 * 왜 키워드 기반인가: 단순하고 빠르며, 알려진 유해 패턴에 대해서는
 * LLM 없이도 확실하게 차단할 수 있다. 미묘한 경우는 [LlmClassificationStage]가 처리한다.
 *
 * @param blockedCategories 차단할 카테고리 집합 (기본값: malware, weapons, self_harm)
 * @param customRules 사용자 정의 분류 규칙 (기본 규칙에 추가됨)
 *
 * @see CompositeClassificationStage 이 단계를 1차로 사용하는 복합 분류기
 * @see LlmClassificationStage LLM 기반 2차 분류기
 * @see com.arc.reactor.guard.ClassificationStage 분류 단계 인터페이스
 */
class RuleBasedClassificationStage(
    private val blockedCategories: Set<String> = DEFAULT_BLOCKED_CATEGORIES,
    customRules: List<ClassificationRule> = emptyList()
) : ClassificationStage {

    override val stageName = "Classification"

    /** 기본 규칙 + 사용자 정의 규칙을 합친 전체 규칙 목록 */
    private val allRules: List<ClassificationRule> = DEFAULT_RULES + customRules

    override suspend fun check(command: GuardCommand): GuardResult {
        val text = command.text.lowercase()

        for (rule in allRules) {
            // 차단 대상 카테고리가 아닌 규칙은 건너뜀
            if (rule.category !in blockedCategories) continue

            // 규칙의 키워드 중 입력에 포함된 개수를 셈
            val matchCount = rule.keywords.count { keyword -> text.contains(keyword) }
            if (matchCount >= rule.minMatchCount) {
                logger.warn {
                    "Classification blocked: category=${rule.category} " +
                        "matches=$matchCount threshold=${rule.minMatchCount}"
                }
                return GuardResult.Rejected(
                    reason = "Content classified as ${rule.category}",
                    category = RejectionCategory.OFF_TOPIC
                )
            }
        }

        return GuardResult.Allowed.DEFAULT
    }

    companion object {
        /** 기본 차단 카테고리: 멀웨어, 무기, 자해 */
        val DEFAULT_BLOCKED_CATEGORIES = setOf("malware", "weapons", "self_harm")

        /** 기본 분류 규칙 목록 */
        val DEFAULT_RULES = listOf(
            // 멀웨어 관련 키워드 — 1개만 매칭되어도 차단
            ClassificationRule(
                category = "malware",
                keywords = listOf("write malware", "create virus", "ransomware code", "keylogger",
                    "trojan horse code", "exploit code for"),
                minMatchCount = 1
            ),
            // 무기 관련 키워드 — 1개만 매칭되어도 차단
            ClassificationRule(
                category = "weapons",
                keywords = listOf("build a bomb", "make explosives", "weapon manufacturing",
                    "synthesize poison", "chemical weapon"),
                minMatchCount = 1
            ),
            // 자해 관련 키워드 — 1개만 매칭되어도 차단
            ClassificationRule(
                category = "self_harm",
                keywords = listOf("how to hurt myself", "suicide methods", "self-harm techniques"),
                minMatchCount = 1
            )
        )
    }
}

/**
 * 분류 규칙 데이터 클래스
 *
 * @property category 규칙의 카테고리명 (blockedCategories에 포함되어야 적용됨)
 * @property keywords 탐지할 키워드 목록 (소문자 비교)
 * @property minMatchCount 거부를 위한 최소 매칭 키워드 수 (기본값: 1)
 */
data class ClassificationRule(
    val category: String,
    val keywords: List<String>,
    val minMatchCount: Int = 1
)
