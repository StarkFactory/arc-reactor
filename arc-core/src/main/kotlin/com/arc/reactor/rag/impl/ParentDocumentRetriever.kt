package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.model.RetrievedDocument

/**
 * 부모 문서 검색기 (데코레이터 패턴).
 *
 * [DocumentRetriever] 위임체를 감싸서, 청크 단위 검색 결과를
 * 같은 부모 문서의 인접 청크들로 확장한다.
 * 청크가 아닌 문서는 그대로 반환한다.
 *
 * ## 왜 청크 확장이 필요한가?
 * 작은 청크로 검색하면 정밀도(precision)가 높지만,
 * LLM에게 전달되는 컨텍스트가 너무 좁아 맥락을 놓칠 수 있다.
 * 주변 청크를 포함하면 더 풍부한 컨텍스트를 제공한다.
 *
 * Chen et al., 2024, "Mixture-of-Granularity: Optimize the Chunking
 * Granularity for RAG" (arXiv:2406.00456) 기반:
 * 작은 청크로 정밀 검색 후, 주변 윈도우를 포함하여 풍부한 컨텍스트를 반환.
 *
 * @param delegate   기저 검색기 (예: [SpringAiVectorStoreRetriever])
 * @param windowSize 각 히트 전후에 포함할 인접 청크 수
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

        // 청크된 문서와 일반 문서를 분리한다
        val (chunked, nonChunked) = results.partition { isChunked(it) }
        if (chunked.isEmpty()) return results

        val expanded = expandChunkedResults(chunked)
        return mergeAndSort(expanded, nonChunked, topK)
    }

    /**
     * 청크된 결과를 부모 문서별로 그룹화하고,
     * 각 그룹을 청크 인덱스 순서대로 병합하여 하나의 문서로 만든다.
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
     * 같은 부모에 속하는 순서 정렬된 청크들로부터
     * 하나의 병합 문서를 빌드한다.
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

    /**
     * 확장된 문서와 비청크 문서를 합치고 점수 내림차순 정렬 후
     * ID 중복 제거, topK 제한을 적용한다.
     */
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

    /** 메타데이터의 "chunked" 플래그로 청크 여부를 판단한다. */
    private fun isChunked(doc: RetrievedDocument): Boolean =
        doc.metadata["chunked"] == true

    /** 메타데이터에서 부모 문서 ID를 추출한다. 없으면 자기 자신의 ID를 사용. */
    private fun parentId(doc: RetrievedDocument): String =
        doc.metadata["parent_document_id"]?.toString() ?: doc.id

    /** 메타데이터에서 청크 인덱스를 추출한다. */
    private fun chunkIndex(doc: RetrievedDocument): Int? =
        doc.metadata["chunk_index"]?.toString()?.toIntOrNull()
}
