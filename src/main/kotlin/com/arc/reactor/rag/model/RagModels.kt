package com.arc.reactor.rag.model

/**
 * RAG 쿼리
 */
data class RagQuery(
    val query: String,
    val filters: Map<String, Any> = emptyMap(),
    val topK: Int = 10,
    val rerank: Boolean = true
)

/**
 * 검색된 문서
 */
data class RetrievedDocument(
    val id: String,
    val content: String,
    val metadata: Map<String, Any> = emptyMap(),
    val score: Double = 0.0,
    val source: String? = null
) {
    /** 토큰 추정 (대략 4글자 = 1토큰) */
    val estimatedTokens: Int get() = content.length / 4
}

/**
 * RAG 컨텍스트 (LLM에 전달)
 */
data class RagContext(
    val context: String,
    val documents: List<RetrievedDocument>,
    val totalTokens: Int = 0
) {
    val hasDocuments: Boolean get() = documents.isNotEmpty()

    companion object {
        val EMPTY = RagContext(
            context = "",
            documents = emptyList()
        )
    }
}

/**
 * 문서 청크
 */
data class DocumentChunk(
    val content: String,
    val index: Int,
    val metadata: Map<String, Any> = emptyMap()
)
