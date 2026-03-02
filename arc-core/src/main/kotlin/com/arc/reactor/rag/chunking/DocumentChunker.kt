package com.arc.reactor.rag.chunking

import org.springframework.ai.document.Document

/**
 * Splits documents into smaller chunks for improved RAG retrieval accuracy.
 *
 * Short documents (below the configured threshold) are returned as-is.
 * Implementations add chunking metadata (parent_document_id, chunk_index, chunk_total)
 * to each chunk for traceability.
 */
interface DocumentChunker {

    /** Split a single document into one or more chunks. */
    fun chunk(document: Document): List<Document>

    /** Split multiple documents, each independently chunked. */
    fun chunk(documents: List<Document>): List<Document> = documents.flatMap { chunk(it) }

    companion object {
        private const val CHUNK_ID_SEPARATOR = ":chunk:"

        /** Deterministic chunk ID from parent document ID and chunk index. */
        fun chunkId(parentId: String, index: Int): String =
            "$parentId$CHUNK_ID_SEPARATOR$index"

        /**
         * Derive all possible chunk IDs for a parent document ID.
         * Used at delete time to clean up chunks without an extra search API call.
         */
        fun deriveChunkIds(parentId: String, maxChunks: Int): List<String> =
            (0 until maxChunks).map { chunkId(parentId, it) }

        /** Check if an ID is already a chunk ID (contains the chunk separator). */
        fun isChunkId(id: String): Boolean = id.contains(CHUNK_ID_SEPARATOR)
    }
}

/**
 * No-op implementation that returns documents unchanged.
 * Used when chunking is disabled.
 */
class NoOpDocumentChunker : DocumentChunker {
    override fun chunk(document: Document): List<Document> = listOf(document)
}
