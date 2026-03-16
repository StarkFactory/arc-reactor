package com.arc.reactor.agent.impl

import com.arc.reactor.hook.model.HookContext

/**
 * 에이전트 실행 파이프라인의 각 단계(guard, hook, RAG, LLM 등) 소요 시간을 기록하고 조회하는 유틸리티.
 *
 * [HookContext.metadata]에 `stageTimings` 키로 `Map<String, Long>`을 저장하여,
 * 실행 완료 후 어떤 단계에서 얼마나 시간이 걸렸는지 분석할 수 있도록 한다.
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
 * 기존에 기록된 다른 단계 타이밍은 보존하면서, 해당 [stage]의 값만 추가/갱신한다.
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

    // ── 단계 1: 기존 타이밍 맵을 복사하여 새 맵에 병합 ──
    val current = hookContext.metadata[STAGE_TIMINGS_METADATA_KEY] as? Map<*, *>
    val updated = LinkedHashMap<String, Any>()
    current?.forEach { (key, value) ->
        if (key is String && value is Number) {
            updated[key] = value.toLong()
        }
    }

    // ── 단계 2: 새 단계 타이밍 추가 후 metadata에 저장 ──
    updated[stage] = normalizedDuration
    hookContext.metadata[STAGE_TIMINGS_METADATA_KEY] = updated
}

/**
 * [HookContext]에 기록된 모든 단계별 타이밍을 조회한다.
 *
 * @param hookContext 타이밍이 저장된 훅 컨텍스트
 * @return 단계 이름 → 소요 시간(밀리초) 맵. 기록이 없으면 빈 맵 반환
 */
internal fun readStageTimings(hookContext: HookContext): Map<String, Long> {
    val current = hookContext.metadata[STAGE_TIMINGS_METADATA_KEY] as? Map<*, *> ?: return emptyMap()
    return current.entries
        .mapNotNull { (key, value) ->
            val stage = key as? String ?: return@mapNotNull null
            val duration = (value as? Number)?.toLong() ?: return@mapNotNull null
            stage to duration
        }
        .toMap(LinkedHashMap())
}
