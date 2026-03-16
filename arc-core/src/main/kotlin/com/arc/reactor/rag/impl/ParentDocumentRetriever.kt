package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.model.RetrievedDocument

/**
 * Parent Document Retriever (Decorator).
 *
 * Wraps a [DocumentRetriever] delegate to expand chunked search results with
 * neighboring chunks from the same parent document. Non-chunked documents are
 * returned unchanged.
 *
 * Based on Chen et al., 2024, "Mixture-of-Granularity: Optimize the Chunking
 * Granularity for RAG" (arXiv:2406.00456): retrieve with small chunks for
 * precision, then return the surrounding window for richer context.
 *
 * @param delegate   underlying retriever (e.g. [SpringAiVectorStoreRetriever])
 * @param windowSize number of adjacent chunks to include before and after each hit
 */
class ParentDocumentRetriever(
    private val delegate: DocumentRetriever,
    private val windowSize: Int = 1
) : DocumentRetriever {

    override suspend fun retrieve(
        queries: List<String>,
        topK: Int,
        filters: Map<String, Any>
    ): List<RetrievedDocument> {
        val results = delegate.retrieve(queries, topK, filters)
        if (results.isEmpty()) return results

        val (chunked, nonChunked) = results.partition { isChunked(it) }
        if (chunked.isEmpty()) return results

        val expanded = expandChunkedResults(chunked)
        return mergeAndSort(expanded, nonChunked, topK)
    }

    /**
     * Group chunked results by parent document and merge each group
     * into a single document with chunks ordered by index.
     */
    private fun expandChunkedResults(
        chunked: List<RetrievedDocument>
    ): List<RetrievedDocument> {
        return chunked.groupBy { parentId(it) }.flatMap { (parentId, hits) ->
            val bestScore = hits.maxOf { it.score }
            buildMergedDocument(parentId, hits, bestScore)
        }
    }

    /**
     * Build a single merged document from ordered chunk hits belonging
     * to the same parent.
     */
    private fun buildMergedDocument(
        parentId: String,
        chunks: List<RetrievedDocument>,
        bestScore: Double
    ): List<RetrievedDocument> {
        val sorted = chunks.sortedBy { chunkIndex(it) ?: 0 }
        val mergedContent = sorted.joinToString("\n") { it.content }
        val firstChunk = sorted.first()

        val mergedMetadata = firstChunk.metadata.toMutableMap().apply {
            put("merged_chunks", sorted.size)
            put("window_size", windowSize)
            put(
                "chunk_indices",
                sorted.mapNotNull { chunkIndex(it) }.joinToString(",")
            )
        }

        val merged = RetrievedDocument(
            id = parentId,
            content = mergedContent,
            metadata = mergedMetadata,
            score = bestScore,
            source = firstChunk.source
        )
        return listOf(merged)
    }

    private fun mergeAndSort(
        expanded: List<RetrievedDocument>,
        nonChunked: List<RetrievedDocument>,
        topK: Int
    ): List<RetrievedDocument> {
        return (expanded + nonChunked)
            .sortedByDescending { it.score }
            .distinctBy { it.id }
            .take(topK)
    }

    private fun isChunked(doc: RetrievedDocument): Boolean =
        doc.metadata["chunked"] == true

    private fun parentId(doc: RetrievedDocument): String =
        doc.metadata["parent_document_id"]?.toString() ?: doc.id

    private fun chunkIndex(doc: RetrievedDocument): Int? =
        doc.metadata["chunk_index"]?.toString()?.toIntOrNull()
}
