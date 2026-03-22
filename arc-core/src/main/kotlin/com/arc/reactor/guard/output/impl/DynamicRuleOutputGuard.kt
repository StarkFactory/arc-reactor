package com.arc.reactor.guard.output.impl

import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import com.arc.reactor.guard.output.policy.OutputGuardRule
import com.arc.reactor.guard.output.policy.OutputGuardRuleAction
import com.arc.reactor.guard.output.policy.OutputGuardRuleEvaluator
import com.arc.reactor.guard.output.policy.OutputGuardRuleInvalidationBus
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 동적 규칙 기반 출력 Guard (order=15)
 *
 * [OutputGuardRuleStore]에 저장된 정규식 규칙을 기반으로 LLM 출력을 검사한다.
 * 규칙은 관리자 API를 통해 런타임에 변경할 수 있으며, 애플리케이션 재시작 없이 적용된다.
 *
 * ## 캐시 전략
 * - 규칙을 메모리에 캐시하여 매 요청마다 DB 조회를 방지한다
 * - [refreshIntervalMs] 간격으로 캐시를 갱신한다 (최소 200ms)
 * - [OutputGuardRuleInvalidationBus]를 통해 관리자가 규칙을 변경하면
 *   revision이 증가하여 캐시를 즉시 무효화한다
 *
 * ## 규칙 평가 흐름
 * 1. 캐시에서 활성화된 규칙을 priority → createdAt 순으로 가져온다
 * 2. [OutputGuardRuleEvaluator]가 각 규칙의 정규식을 콘텐츠에 매칭한다
 * 3. REJECT 규칙이 매칭되면 응답을 즉시 차단한다
 * 4. MASK 규칙이 매칭되면 해당 부분을 "[REDACTED]"로 치환한다
 *
 * @param store 동적 규칙 저장소
 * @param refreshIntervalMs 캐시 갱신 간격 (밀리초, 최소 200ms)
 * @param invalidationBus 규칙 변경 시 캐시 무효화 버스
 * @param evaluator 규칙 평가 엔진
 *
 * @see OutputGuardRuleStore 규칙 저장소 인터페이스
 * @see OutputGuardRuleEvaluator 규칙 평가 엔진
 * @see OutputGuardRuleInvalidationBus 캐시 무효화 버스
 */
class DynamicRuleOutputGuard(
    private val store: OutputGuardRuleStore,
    private val refreshIntervalMs: Long = 3000,
    private val invalidationBus: OutputGuardRuleInvalidationBus = OutputGuardRuleInvalidationBus(),
    private val evaluator: OutputGuardRuleEvaluator = OutputGuardRuleEvaluator()
) : OutputGuardStage {

    override val stageName: String = "DynamicRule"
    override val order: Int = 15

    /** 마지막 캐시 갱신 시각 (밀리초) */
    @Volatile
    private var cachedAtMs: Long = 0

    /** 마지막 캐시 시점의 규칙 revision */
    @Volatile
    private var cachedRevision: Long = -1

    /** 캐시된 활성 규칙 목록 */
    @Volatile
    private var cachedRules: List<OutputGuardRule> = emptyList()

    /** 코루틴 안전한 캐시 갱신 락 (synchronized 대체) */
    private val cacheMutex = Mutex()

    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        val rules = getRules()
        if (rules.isEmpty()) return OutputGuardResult.Allowed.DEFAULT

        val evaluation = evaluator.evaluate(content = content, rules = rules)

        for (invalid in evaluation.invalidRules) {
            logger.warn { "Skipping invalid dynamic output rule id=${invalid.ruleId}, name=${invalid.ruleName}" }
        }

        if (evaluation.blocked) {
            val blockedBy = evaluation.blockedBy?.ruleName ?: "unknown"
            logger.warn { "Dynamic output rule '$blockedBy' matched, rejecting response" }
            return OutputGuardResult.Rejected(
                reason = "Response blocked: $blockedBy",
                category = OutputRejectionCategory.POLICY_VIOLATION,
                stage = stageName
            )
        }

        if (!evaluation.modified) return OutputGuardResult.Allowed.DEFAULT

        return OutputGuardResult.Modified(
            content = evaluation.content,
            reason = "Dynamic rule masked: ${
                evaluation.matchedRules
                    .filter { it.action == OutputGuardRuleAction.MASK }
                    .joinToString(", ") { it.ruleName }
            }",
            stage = stageName
        )
    }

    /**
     * 규칙을 가져온다. 캐시 유효 기간 내이고 revision이 변경되지 않았으면 캐시 반환.
     * 그렇지 않으면 저장소에서 새로 로드한다.
     *
     * Mutex 기반 double-checked locking으로 코루틴 캐리어 스레드 블로킹을 방지한다.
     */
    private suspend fun getRules(nowMs: Long = System.currentTimeMillis()): List<OutputGuardRule> {
        val interval = refreshIntervalMs.coerceAtLeast(200)
        val revision = invalidationBus.currentRevision()
        if (nowMs - cachedAtMs <= interval && revision == cachedRevision) return cachedRules

        cacheMutex.withLock {
            val latestRevision = invalidationBus.currentRevision()
            if (nowMs - cachedAtMs <= interval && latestRevision == cachedRevision) return cachedRules

            cachedRules = store.list()
                .asSequence()
                .filter { it.enabled }
                .sortedWith(compareBy<OutputGuardRule> { it.priority }.thenBy { it.createdAt })
                .toList()
            cachedAtMs = nowMs
            cachedRevision = latestRevision
            return cachedRules
        }
    }
}
