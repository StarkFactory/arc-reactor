package com.arc.reactor.rag.model

/**
 * RAG query
 */
data class RagQuery(
    val query: String,
    val filters: Map<String, Any> = emptyMap(),
    val topK: Int = 10,
    val rerank: Boolean = true
)

/**
 * Retrieved document
 */
data class RetrievedDocument(
    val id: String,
    val content: String,
    val metadata: Map<String, Any> = emptyMap(),
    val score: Double = 0.0,
    val source: String? = null
) {
    /** Estimated token count (approximately 4 chars = 1 token) */
    val estimatedTokens: Int get() = content.length / 4
}

/**
 * RAG context (passed to LLM)
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
 * Document chunk
 */
data class DocumentChunk(
    val content: String,
    val index: Int,
    val metadata: Map<String, Any> = emptyMap()
)
