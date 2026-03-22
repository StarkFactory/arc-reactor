package com.arc.reactor.agent.budget

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 비용 이상 탐지 Hook — 에이전트 완료 후 비용을 기록하고 이상을 평가한다.
 *
 * 매 요청 완료 시:
 * 1. [HookContext.metadata]에서 `costEstimateUsd` 추출
 * 2. [CostAnomalyDetector]에 비용 기록
 * 3. 이상 감지 시 WARN 로깅
 *
 * Hook은 fail-open으로 동작한다: 비용 이상 평가 실패가 에이전트 응답을 차단하지 않는다.
 *
 * @param detector 비용 이상 탐지기
 */
class CostAnomalyHook(
    private val detector: CostAnomalyDetector
) : AfterAgentCompleteHook {

    /** 후기 Hook (알림 발송): 200+ 범위 */
    override val order: Int = 220

    /** 비용 이상 평가 실패가 에이전트 실행을 차단하면 안 됨 */
    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(
        context: HookContext,
        response: AgentResponse
    ) {
        try {
            val cost = extractCost(context) ?: return
            detector.recordCost(cost)

            val anomaly = detector.evaluate()
            if (anomaly != null) {
                logger.warn { "비용 이상 탐지: ${anomaly.message}" }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "비용 이상 평가 실패: ${e.message}" }
        }
    }

    /** HookContext 메타데이터에서 비용을 추출한다. */
    private fun extractCost(context: HookContext): Double? {
        val raw = context.metadata["costEstimateUsd"] ?: return null
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
    }
}
