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

    suspend fun retrieve(command: AgentCommand): RagContext? {
        if (!enabled || ragPipeline == null) return null

        val startTime = System.currentTimeMillis()
        return try {
            withTimeout(retrievalTimeoutMs) {
                val effectiveTopK = resolveTopK(command.userPrompt)
                    ?: return@withTimeout null
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
                    logger.debug { "RAG retrieval succeeded with ${ragResult.documents.size} documents in ${durationMs}ms" }
                    metrics.recordRagRetrieval("success", durationMs)
                    ragResult
                } else {
                    logger.info { "RAG retrieval returned empty results in ${durationMs}ms" }
                    metrics.recordRagRetrieval("empty", durationMs)
                    null
                }
            }
        } catch (e: TimeoutCancellationException) {
            val durationMs = System.currentTimeMillis() - startTime
            logger.warn { "RAG retrieval timed out after ${retrievalTimeoutMs}ms, continuing without context" }
            metrics.recordRagRetrieval("timeout", durationMs)
            null
        } catch (e: Exception) {
            e.throwIfCancellation()
            val durationMs = System.currentTimeMillis() - startTime
            logger.error(e) { "RAG retrieval failed after ${durationMs}ms, continuing without context" }
            metrics.recordRagRetrieval("error", durationMs)
            null
        }
    }

    /**
     * Resolve the effective topK based on adaptive routing.
     * Returns null when routing determines NO_RETRIEVAL.
     */
    private suspend fun resolveTopK(query: String): Int? {
        if (queryRouter == null) return topK

        return when (queryRouter.route(query)) {
            QueryComplexity.NO_RETRIEVAL -> {
                logger.debug { "Adaptive routing: NO_RETRIEVAL, skipping RAG" }
                null
            }
            QueryComplexity.SIMPLE -> {
                logger.debug { "Adaptive routing: SIMPLE, topK=$topK" }
                topK
            }
            QueryComplexity.COMPLEX -> {
                logger.debug { "Adaptive routing: COMPLEX, topK=$complexTopK" }
                complexTopK
            }
        }
    }

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
