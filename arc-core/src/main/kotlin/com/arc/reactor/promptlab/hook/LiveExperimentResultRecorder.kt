package com.arc.reactor.promptlab.hook

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.promptlab.LiveExperimentStore
import com.arc.reactor.promptlab.model.ExperimentResult
import com.arc.reactor.promptlab.model.PromptVariant
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 라이브 A/B 테스트 결과 기록 훅.
 *
 * 에이전트 실행 완료 후, 메타데이터에 실험 정보가 포함된 경우
 * 실행 결과(성공/실패, 지연시간)를 [LiveExperimentStore]에 기록한다.
 *
 * ## 동작
 * - 순서 280: ExperimentCaptureHook(270) 이후
 * - Fail-open: 결과 기록 실패가 에이전트 응답에 영향 없음
 * - 메타데이터에 [LIVE_EXPERIMENT_ID_KEY]가 없으면 무시
 *
 * ## 메타데이터 키
 * [PromptExperimentRouter]가 라우팅 결정 시 다음 키를 설정:
 * - `live.experiment.id`: 실험 ID
 * - `live.experiment.variant`: CONTROL 또는 VARIANT
 *
 * @param store 결과를 기록할 라이브 실험 저장소
 * @see com.arc.reactor.promptlab.PromptExperimentRouter 라우팅 결정
 * @see LiveExperimentStore 저장소
 */
class LiveExperimentResultRecorder(
    private val store: LiveExperimentStore
) : AfterAgentCompleteHook {

    override val order: Int = 280
    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(
        context: HookContext,
        response: AgentResponse
    ) {
        try {
            val experimentId = context.metadata[LIVE_EXPERIMENT_ID_KEY]
                ?.toString() ?: return
            val variantStr = context.metadata[LIVE_EXPERIMENT_VARIANT_KEY]
                ?.toString() ?: return
            val variant = try {
                PromptVariant.valueOf(variantStr)
            } catch (_: IllegalArgumentException) {
                logger.warn { "알 수 없는 variant 값: $variantStr" }
                return
            }

            val sessionId = context.metadata[SESSION_ID_KEY]?.toString()

            val result = ExperimentResult(
                experimentId = experimentId,
                variant = variant,
                success = response.success,
                latencyMs = response.totalDurationMs,
                sessionId = sessionId
            )
            store.recordResult(result)

            logger.debug {
                "라이브 실험 결과 기록: " +
                    "experiment=$experimentId, variant=$variant, " +
                    "success=${response.success}"
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "실험 결과 기록 실패: ${e.message}" }
        }
    }

    companion object {
        /** 메타데이터 키: 라이브 실험 ID */
        const val LIVE_EXPERIMENT_ID_KEY = "live.experiment.id"

        /** 메타데이터 키: 라우팅된 변형 (CONTROL/VARIANT) */
        const val LIVE_EXPERIMENT_VARIANT_KEY = "live.experiment.variant"

        /** 메타데이터 키: 세션 ID (결정론적 라우팅 추적용) */
        const val SESSION_ID_KEY = "live.experiment.sessionId"
    }
}
