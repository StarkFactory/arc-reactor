package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
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
    private val retrievalTimeoutMs: Long = 5000
) {

    suspend fun retrieve(command: AgentCommand): String? {
        if (!enabled || ragPipeline == null) return null

        return try {
            withTimeout(retrievalTimeoutMs) {
                val ragFilters = extractRagFilters(command.metadata)
                val ragResult = ragPipeline.retrieve(
                    RagQuery(
                        query = command.userPrompt,
                        filters = ragFilters,
                        topK = topK,
                        rerank = rerankEnabled
                    )
                )
                if (ragResult.hasDocuments) ragResult.context else null
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "RAG retrieval timed out after ${retrievalTimeoutMs}ms, continuing without context" }
            null
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "RAG retrieval failed, continuing without context" }
            null
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
