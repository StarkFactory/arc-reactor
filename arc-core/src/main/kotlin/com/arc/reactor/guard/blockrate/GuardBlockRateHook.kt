package com.arc.reactor.guard.blockrate

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Guard 차단률 모니터링 Hook — 에이전트 완료 후 Guard 판정 결과를 기록하고
 * 주기적으로 차단률 이상을 평가한다.
 *
 * 매 요청 완료 시:
 * 1. [HookContext.metadata]에서 `guardBlocked` 추출
 * 2. [GuardBlockRateMonitor]에 결과 기록
 * 3. [evaluationInterval]회마다 이상을 평가하여 WARN 로깅
 *
 * Hook은 fail-open으로 동작한다: 차단률 평가 실패가 에이전트 응답을 차단하지 않는다.
 *
 * @param monitor Guard 차단률 모니터
 * @param evaluationInterval 평가 주기 (요청 N회마다 평가)
 */
class GuardBlockRateHook(
    private val monitor: GuardBlockRateMonitor,
    private val evaluationInterval: Int = 20
) : AfterAgentCompleteHook {

    /** 후기 Hook (모니터링): 200+ 범위 */
    override val order: Int = 240

    /** 차단률 평가 실패가 에이전트 실행을 차단하면 안 됨 */
    override val failOnError: Boolean = false

    private val requestCount = AtomicLong(0)

    override suspend fun afterAgentComplete(
        context: HookContext,
        response: AgentResponse
    ) {
        try {
            val blocked = extractGuardBlocked(context) ?: return
            monitor.recordGuardResult(blocked)

            val count = requestCount.incrementAndGet()
            if (count % evaluationInterval == 0L) {
                val anomalies = monitor.evaluate()
                for (anomaly in anomalies) {
                    logger.warn { "Guard 차단률 이상 감지: ${anomaly.message}" }
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "Guard 차단률 평가 실패: ${e.message}" }
        }
    }

    /** HookContext 메타데이터에서 Guard 차단 여부를 추출한다. */
    private fun extractGuardBlocked(context: HookContext): Boolean? {
        val raw = context.metadata["guardBlocked"] ?: return null
        return when (raw) {
            is Boolean -> raw
            is String -> raw.toBooleanStrictOrNull()
            else -> null
        }
    }
}
