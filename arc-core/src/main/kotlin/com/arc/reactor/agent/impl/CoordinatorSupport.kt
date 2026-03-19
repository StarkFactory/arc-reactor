package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.response.VerifiedSource

/**
 * AgentExecutionCoordinator / StreamingExecutionCoordinator 공통 유틸리티.
 *
 * 두 Coordinator에서 동일하게 사용하는 RAG 출처 등록, 단계별 레이턴시 기록 로직을 공유한다.
 */

/** RAG 검색 결과의 문서 출처를 HookContext의 verifiedSources에 등록한다. */
internal fun registerRagVerifiedSources(ragResult: RagContext?, hookContext: HookContext) {
    if (ragResult == null || !ragResult.hasDocuments) return
    for (doc in ragResult.documents) {
        val source = doc.source?.takeIf { it.isNotBlank() } ?: continue
        hookContext.verifiedSources.add(
            VerifiedSource(
                title = doc.metadata["title"]?.toString() ?: doc.id,
                url = source,
                toolName = "rag"
            )
        )
    }
}

/** 루프 내부 단계(llm_calls, tool_execution 등)의 레이턴시를 메트릭에 기록한다. */
internal fun recordLoopStageLatency(
    hookContext: HookContext,
    metadata: Map<String, Any>,
    stage: String,
    agentMetrics: AgentMetrics
) {
    val durationMs = readStageTimings(hookContext)[stage] ?: return
    agentMetrics.recordStageLatency(stage, durationMs, metadata)
}
