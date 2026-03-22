package com.arc.reactor.promptlab

import com.arc.reactor.promptlab.model.LiveExperimentStatus
import com.arc.reactor.promptlab.model.PromptExperiment
import com.arc.reactor.promptlab.model.PromptVariant
import mu.KotlinLogging
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/**
 * 라이브 프롬프트 A/B 테스트 라우팅 결과.
 *
 * @param experimentId 적용된 실험 ID
 * @param variant 선택된 변형
 * @param prompt 사용할 시스템 프롬프트
 */
data class RoutingDecision(
    val experimentId: String,
    val variant: PromptVariant,
    val prompt: String
)

/**
 * 프롬프트 A/B 테스트 라우터.
 *
 * RUNNING 상태의 실험이 있을 때, 요청의 시스템 프롬프트를
 * control(기존) 또는 variant(새 후보)로 라우팅한다.
 *
 * ## 라우팅 규칙
 * 1. sessionId가 있으면 해시 기반 결정론적 라우팅 (동일 세션 = 동일 변형)
 * 2. sessionId가 없으면 확률적 라우팅 (trafficPercent 비율)
 * 3. RUNNING 실험이 없으면 null 반환 (원래 프롬프트 사용)
 *
 * ## 세션 결정론성
 * `sessionId`의 해시값을 사용하여 같은 세션의 모든 요청이
 * 항상 동일한 변형(control/variant)을 사용하도록 보장한다.
 * 사용자 경험의 일관성을 위해 중요하다.
 *
 * @param store 라이브 실험 저장소
 * @see LiveExperimentStore 실험 데이터 소스
 * @see com.arc.reactor.promptlab.hook.LiveExperimentResultRecorder 결과 기록 훅
 */
class PromptExperimentRouter(
    private val store: LiveExperimentStore
) {

    /**
     * 현재 요청에 적용할 프롬프트 변형을 결정한다.
     *
     * @param currentPrompt 현재 시스템 프롬프트 (실험의 control과 매칭 확인)
     * @param sessionId 세션 식별자 (결정론적 라우팅용, null이면 확률적)
     * @return 라우팅 결정, 또는 적용할 실험이 없으면 null
     */
    fun route(
        currentPrompt: String,
        sessionId: String? = null
    ): RoutingDecision? {
        val experiments = store.listRunning()
        if (experiments.isEmpty()) return null

        for (experiment in experiments) {
            val decision = routeForExperiment(
                experiment, currentPrompt, sessionId
            )
            if (decision != null) return decision
        }
        return null
    }

    /**
     * 특정 실험에 대한 라우팅을 결정한다.
     *
     * control 프롬프트가 현재 프롬프트와 일치하는 실험만 적용한다.
     * 매칭되는 실험이 아니면 null을 반환한다.
     */
    private fun routeForExperiment(
        experiment: PromptExperiment,
        currentPrompt: String,
        sessionId: String?
    ): RoutingDecision? {
        if (experiment.controlPrompt != currentPrompt) return null

        val variant = selectVariant(experiment, sessionId)
        val prompt = when (variant) {
            PromptVariant.CONTROL -> experiment.controlPrompt
            PromptVariant.VARIANT -> experiment.variantPrompt
        }

        logger.debug {
            "Routed to $variant for experiment=${experiment.id}"
        }

        return RoutingDecision(
            experimentId = experiment.id,
            variant = variant,
            prompt = prompt
        )
    }

    /**
     * 트래픽 비율과 세션 ID에 기반하여 변형을 선택한다.
     *
     * sessionId가 있으면 해시 기반 결정론적 선택,
     * 없으면 랜덤 확률적 선택을 수행한다.
     */
    internal fun selectVariant(
        experiment: PromptExperiment,
        sessionId: String?
    ): PromptVariant {
        val threshold = experiment.trafficPercent

        val value = if (sessionId != null) {
            deterministicValue(experiment.id, sessionId)
        } else {
            randomValue()
        }

        return if (value < threshold) {
            PromptVariant.VARIANT
        } else {
            PromptVariant.CONTROL
        }
    }

    /**
     * sessionId + experimentId 해시로 결정론적 0-99 값을 생성한다.
     *
     * 같은 세션 + 같은 실험 = 항상 같은 결과.
     */
    private fun deterministicValue(
        experimentId: String,
        sessionId: String
    ): Int {
        val combined = "$experimentId:$sessionId"
        return abs(combined.hashCode() % BUCKET_SIZE)
    }

    /** 확률적 0-99 값을 생성한다. */
    private fun randomValue(): Int = (Math.random() * BUCKET_SIZE).toInt()

    companion object {
        private const val BUCKET_SIZE = 100
    }
}
