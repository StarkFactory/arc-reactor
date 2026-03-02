package com.arc.reactor.rag.ingestion

import com.arc.reactor.rag.chunking.DocumentChunker
import org.springframework.ai.document.Document
import java.util.UUID

fun RagIngestionCandidate.toDocument(documentId: String = UUID.randomUUID().toString()): Document {
    val metadata = linkedMapOf<String, Any>(
        "source" to "rag_ingestion_candidate",
        "runId" to runId,
        "userId" to userId,
        "capturedAt" to capturedAt.toString()
    )
    sessionId?.takeIf { it.isNotBlank() }?.let { metadata["sessionId"] = it }
    channel?.takeIf { it.isNotBlank() }?.let { metadata["channel"] = it }

    return Document(documentId, buildRagQaContent(query, response), metadata)
}

/**
 * Converts a candidate to one or more documents, applying chunking if a chunker is provided.
 * Short Q&A documents will typically stay as a single chunk (below minChunkThreshold).
 */
fun RagIngestionCandidate.toDocuments(
    documentId: String = UUID.randomUUID().toString(),
    chunker: DocumentChunker? = null
): List<Document> {
    val document = this.toDocument(documentId)
    return chunker?.chunk(document) ?: listOf(document)
}

private fun buildRagQaContent(query: String, response: String): String {
    return buildString {
        append("Q: ")
        append(query.trim())
        append("\n\nA: ")
        append(response.trim())
    }
}
