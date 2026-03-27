package com.arc.reactor.agent.impl

import com.arc.reactor.hook.model.HookContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 에이전트 실행 파이프라인의 각 단계(guard, hook, RAG, LLM 등) 소요 시간을 기록하고 조회하는 유틸리티.
 *
 * [HookContext.metadata]에 `stageTimings` 키로 [ConcurrentHashMap]<String, Long>을 저장하여,
 * 실행 완료 후 어떤 단계에서 얼마나 시간이 걸렸는지 분석할 수 있도록 한다.
 * 병렬 단계(History+RAG 등)의 동시 기록을 위해 ConcurrentHashMap을 사용한다.
 *
 * @see com.arc.reactor.agent.impl.PreExecutionResolver guard/hook 단계에서 타이밍 기록
 * @see com.arc.reactor.agent.impl.SpringAiAgentExecutor ReAct 루프에서 단계별 타이밍 기록
 * @see com.arc.reactor.hook.model.HookContext 타이밍 데이터가 저장되는 컨텍스트
 */

/** HookContext metadata에 단계별 타이밍을 저장할 때 사용하는 키 */
internal const val STAGE_TIMINGS_METADATA_KEY = "stageTimings"

/**
 * 특정 단계의 소요 시간을 [HookContext]에 기록한다.
 *
 * metadata에 [ConcurrentHashMap]을 한 번만 생성하고 이후에는 in-place로 갱신한다.
 * 음수 duration은 0으로 보정된다.
 *
 * @param hookContext 타이밍을 저장할 훅 컨텍스트
 * @param stage 단계 이름 (예: "guard", "before_hooks", "intent_resolution")
 * @param durationMs 소요 시간(밀리초)
 */
internal fun recordStageTiming(
    hookContext: HookContext,
    stage: String,
    durationMs: Long
) {
    val normalizedDuration = durationMs.coerceAtLeast(0)
    val timings = hookContext.metadata.getOrPut(STAGE_TIMINGS_METADATA_KEY) {
        ConcurrentHashMap<String, Long>()
    } as? MutableMap<String, Long>
        ?: mutableMapOf<String, Long>().also { hookContext.metadata[STAGE_TIMINGS_METADATA_KEY] = it }
    timings[stage] = normalizedDuration
}

/**
 * [HookContext]에 기록된 모든 단계별 타이밍을 조회한다.
 *
 * @param hookContext 타이밍이 저장된 훅 컨텍스트
 * @return 단계 이름 → 소요 시간(밀리초) 맵. 기록이 없으면 빈 맵 반환
 */
internal fun readStageTimings(hookContext: HookContext): Map<String, Long> {
    return hookContext.metadata[STAGE_TIMINGS_METADATA_KEY] as? Map<String, Long>
        ?: emptyMap()
}
