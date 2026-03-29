package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.rag.QueryComplexity
import com.arc.reactor.rag.QueryRouter
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 에이전트 실행 시 RAG(Retrieval-Augmented Generation) 컨텍스트를 검색하는 retriever.
 *
 * 사용자 프롬프트를 기반으로 벡터 스토어에서 관련 문서를 검색하고,
 * 시스템 프롬프트에 주입할 [RagContext]를 반환한다.
 *
 * 주요 기능:
 * - **적응형 라우팅**: [QueryRouter]가 쿼리 복잡도를 분석하여 topK를 동적 조정
 * - **타임아웃**: 검색이 지정된 시간 내에 완료되지 않으면 RAG 없이 진행
 * - **Fail-safe**: 검색 실패 시 null을 반환하여 RAG 없이 에이전트가 계속 실행됨
 *
 * @see SystemPromptBuilder RAG 컨텍스트를 시스템 프롬프트에 주입
 * @see RagPipeline RAG 검색 파이프라인 (벡터 검색 + rerank)
 * @see QueryRouter 적응형 라우팅을 위한 쿼리 복잡도 분류기
 */
internal class RagContextRetriever(
    private val enabled: Boolean,
    private val topK: Int,
    private val rerankEnabled: Boolean,
    private val ragPipeline: RagPipeline?,
    private val retrievalTimeoutMs: Long = 5000,
    private val metrics: AgentMetrics = NoOpAgentMetrics(),
    private val queryRouter: QueryRouter? = null,
    private val complexTopK: Int = 15
) {

    /**
     * 사용자 프롬프트를 기반으로 RAG 컨텍스트를 검색한다.
     *
     * @param command 에이전트 명령 (userPrompt, metadata 사용)
     * @return 검색된 RAG 컨텍스트. 비활성/타임아웃/에러/빈 결과 시 null
     */
    suspend fun retrieve(command: AgentCommand): RagContext? {
        if (!enabled || ragPipeline == null) return null

        val startTime = System.currentTimeMillis()
        return try {
            withTimeout(retrievalTimeoutMs) {
                // ── 단계 1: 적응형 라우팅으로 topK 결정 (NO_RETRIEVAL이면 검색 생략) ──
                val effectiveTopK = resolveTopK(command.userPrompt)
                    ?: return@withTimeout null
                // ── 단계 2: RAG 파이프라인으로 문서 검색 ──
                val ragFilters = extractRagFilters(command.metadata)
                val ragResult = ragPipeline.retrieve(
                    RagQuery(
                        query = command.userPrompt,
                        filters = ragFilters,
                        topK = effectiveTopK,
                        rerank = rerankEnabled
                    )
                )
                val durationMs = System.currentTimeMillis() - startTime
                if (ragResult.hasDocuments) {
                    logger.debug { "RAG 검색 성공: 문서 ${ragResult.documents.size}건, ${durationMs}ms" }
                    metrics.recordRagRetrieval("success", durationMs)
                    ragResult
                } else {
                    logger.info { "RAG 검색 결과 없음: ${durationMs}ms" }
                    metrics.recordRagRetrieval("empty", durationMs)
                    null
                }
            }
        } catch (e: TimeoutCancellationException) {
            val durationMs = System.currentTimeMillis() - startTime
            logger.warn { "RAG 검색 타임아웃: ${retrievalTimeoutMs}ms 경과, 컨텍스트 없이 계속 진행" }
            metrics.recordRagRetrieval("timeout", durationMs)
            null
        } catch (e: Exception) {
            e.throwIfCancellation()
            val durationMs = System.currentTimeMillis() - startTime
            logger.error(e) { "RAG 검색 실패: ${durationMs}ms 경과, 컨텍스트 없이 계속 진행" }
            metrics.recordRagRetrieval("error", durationMs)
            null
        }
    }

    /**
     * 적응형 라우팅을 기반으로 유효한 topK 값을 결정한다.
     *
     * [QueryRouter]가 없으면 기본 topK를 반환한다.
     * 쿼리 복잡도에 따라: NO_RETRIEVAL → null(검색 생략), SIMPLE → topK, COMPLEX → complexTopK
     *
     * @return 유효한 topK 값. NO_RETRIEVAL인 경우 null
     */
    private suspend fun resolveTopK(query: String): Int? {
        if (queryRouter == null) return topK

        return when (queryRouter.route(query)) {
            QueryComplexity.NO_RETRIEVAL -> {
                logger.debug { "적응형 라우팅: NO_RETRIEVAL, RAG 검색 생략" }
                null
            }
            QueryComplexity.SIMPLE -> {
                logger.debug { "적응형 라우팅: SIMPLE, topK=$topK" }
                topK
            }
            QueryComplexity.COMPLEX -> {
                logger.debug { "적응형 라우팅: COMPLEX, topK=$complexTopK" }
                complexTopK
            }
        }
    }

    /**
     * metadata에서 RAG 필터를 추출한다.
     *
     * 두 가지 소스를 병합한다:
     * 1. `ragFilters` 키의 명시적 필터 맵 (우선)
     * 2. `rag.filter.` 접두사를 가진 개별 메타데이터 키
     */
    private fun extractRagFilters(metadata: Map<String, Any>): Map<String, Any> {
        if (metadata.isEmpty()) return emptyMap()

        val merged = linkedMapOf<String, Any>()

        val explicit = metadata["ragFilters"] as? Map<*, *>
        explicit?.forEach { (k, v) ->
            val key = k?.toString()?.trim().orEmpty()
            if (key.isNotBlank() && v != null) {
                merged[key] = v
            }
        }

        metadata.forEach { (k, v) ->
            if (!k.startsWith("rag.filter.")) return@forEach
            val key = k.removePrefix("rag.filter.").trim()
            if (key.isNotBlank() && key !in merged) {
                merged[key] = v
            }
        }

        return merged
    }
}
