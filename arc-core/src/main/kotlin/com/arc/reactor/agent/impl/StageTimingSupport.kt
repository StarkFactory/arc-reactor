package com.arc.reactor.agent.impl

import com.arc.reactor.hook.model.HookContext

internal const val STAGE_TIMINGS_METADATA_KEY = "stageTimings"

internal fun recordStageTiming(
    hookContext: HookContext,
    stage: String,
    durationMs: Long
) {
    val normalizedDuration = durationMs.coerceAtLeast(0)
    val current = hookContext.metadata[STAGE_TIMINGS_METADATA_KEY] as? Map<*, *>
    val updated = LinkedHashMap<String, Any>()
    current?.forEach { (key, value) ->
        if (key is String && value is Number) {
            updated[key] = value.toLong()
        }
    }
    updated[stage] = normalizedDuration
    hookContext.metadata[STAGE_TIMINGS_METADATA_KEY] = updated
}

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
