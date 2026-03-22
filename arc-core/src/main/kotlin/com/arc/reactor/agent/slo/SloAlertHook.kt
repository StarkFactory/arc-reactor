package com.arc.reactor.agent.slo

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * SLO 알림 Hook — 에이전트 완료 후 메트릭을 기록하고 SLO 위반을 평가한다.
 *
 * 매 요청 완료 시:
 * 1. 레이턴시와 성공/실패를 [SloAlertEvaluator]에 기록
 * 2. 임계값 초과 여부를 평가
 * 3. 위반 발생 시 [SloAlertNotifier]로 알림 발송
 *
 * Hook은 fail-open으로 동작한다: SLO 평가 실패가 에이전트 응답을 차단하지 않는다.
 *
 * @param evaluator SLO 평가기
 * @param notifier SLO 위반 알림 발송기
 */
class SloAlertHook(
    private val evaluator: SloAlertEvaluator,
    private val notifier: SloAlertNotifier
) : AfterAgentCompleteHook {

    /** 후기 Hook (알림 발송): 200+ 범위 */
    override val order: Int = 210

    /** SLO 평가 실패가 에이전트 실행을 차단하면 안 됨 */
    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(
        context: HookContext,
        response: AgentResponse
    ) {
        try {
            evaluator.recordLatency(response.totalDurationMs)
            evaluator.recordResult(response.success)

            val violations = evaluator.evaluate()
            if (violations.isNotEmpty()) {
                notifier.notify(violations)
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "SLO 알림 평가 실패: ${e.message}" }
        }
    }
}
