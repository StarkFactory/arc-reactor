package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.rag.QueryComplexity
import com.arc.reactor.rag.QueryRouter
import com.arc.reactor.rag.RagPipeline
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

    /**
     * Retrieves RAG context for the given command.
     * Returns the context string for backward compatibility.
     */
    suspend fun retrieve(command: AgentCommand): String? {
        return retrieveWithMetadata(command).context
    }

    /**
     * Retrieves RAG context with metadata (document count, source list).
     * Use this when the caller needs retrieval details beyond the context string.
     */
    suspend fun retrieveWithMetadata(command: AgentCommand): RagRetrievalResult {
        if (!enabled || ragPipeline == null) return RagRetrievalResult.EMPTY

        val startTime = System.currentTimeMillis()
        return try {
            withTimeout(retrievalTimeoutMs) {
                val effectiveTopK = resolveTopK(command.userPrompt)
                    ?: return@withTimeout RagRetrievalResult.EMPTY
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
                    val sources = ragResult.documents
                        .mapNotNull { it.source }
                        .distinct()
                    RagRetrievalResult(
                        context = ragResult.context,
                        documentCount = ragResult.documents.size,
                        sources = sources
                    )
                } else {
                    logger.info { "RAG retrieval returned empty results in ${durationMs}ms" }
                    metrics.recordRagRetrieval("empty", durationMs)
                    RagRetrievalResult.EMPTY
                }
            }
        } catch (e: TimeoutCancellationException) {
            val durationMs = System.currentTimeMillis() - startTime
            logger.warn { "RAG retrieval timed out after ${retrievalTimeoutMs}ms, continuing without context" }
            metrics.recordRagRetrieval("timeout", durationMs)
            RagRetrievalResult.EMPTY
        } catch (e: Exception) {
            e.throwIfCancellation()
            val durationMs = System.currentTimeMillis() - startTime
            logger.error(e) { "RAG retrieval failed after ${durationMs}ms, continuing without context" }
            metrics.recordRagRetrieval("error", durationMs)
            RagRetrievalResult.EMPTY
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

    companion object {
        const val METADATA_RAG_DOCUMENT_COUNT = "ragDocumentCount"
        const val METADATA_RAG_SOURCES = "ragSources"
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

/**
 * Result of RAG retrieval including context and metadata.
 */
internal data class RagRetrievalResult(
    val context: String?,
    val documentCount: Int = 0,
    val sources: List<String> = emptyList()
) {

    /**
     * Enriches the hook context metadata with RAG document count and sources
     * when documents were retrieved. No-op when [documentCount] is zero.
     */
    fun enrichMetadata(metadata: MutableMap<String, Any>) {
        if (documentCount <= 0) return
        metadata[RagContextRetriever.METADATA_RAG_DOCUMENT_COUNT] = documentCount
        if (sources.isNotEmpty()) {
            metadata[RagContextRetriever.METADATA_RAG_SOURCES] = sources
        }
    }

    companion object {
        val EMPTY = RagRetrievalResult(context = null)
    }
}
