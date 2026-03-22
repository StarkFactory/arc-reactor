package com.arc.reactor.agent.drift

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * 프롬프트 드리프트 감지 Hook — 에이전트 완료 후 입출력 길이를 기록하고
 * 주기적으로 드리프트를 평가한다.
 *
 * 매 요청 완료 시:
 * 1. [HookContext.userPrompt] 길이를 입력으로 기록
 * 2. [AgentResponse.response] 길이를 출력으로 기록
 * 3. [evaluationInterval]회마다 드리프트를 평가하여 WARN 로깅
 *
 * Hook은 fail-open으로 동작한다: 드리프트 평가 실패가 에이전트 응답을 차단하지 않는다.
 *
 * @param detector 프롬프트 드리프트 감지기
 * @param evaluationInterval 드리프트 평가 주기 (요청 N회마다 평가)
 */
class PromptDriftHook(
    private val detector: PromptDriftDetector,
    private val evaluationInterval: Int = 10
) : AfterAgentCompleteHook {

    /** 후기 Hook (모니터링): 200+ 범위 */
    override val order: Int = 230

    /** 드리프트 평가 실패가 에이전트 실행을 차단하면 안 됨 */
    override val failOnError: Boolean = false

    private val requestCount = AtomicLong(0)

    override suspend fun afterAgentComplete(
        context: HookContext,
        response: AgentResponse
    ) {
        try {
            detector.recordInput(context.userPrompt.length)
            detector.recordOutput(response.response.orEmpty().length)

            val count = requestCount.incrementAndGet()
            if (count % evaluationInterval == 0L) {
                val anomalies = detector.evaluate()
                for (anomaly in anomalies) {
                    logger.warn { "프롬프트 드리프트 감지: ${anomaly.message}" }
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "프롬프트 드리프트 평가 실패: ${e.message}" }
        }
    }
}
