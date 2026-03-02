package com.arc.reactor.rag.chunking

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.document.Document

/**
 * Metrics decorator for [DocumentChunker].
 *
 * Records chunking operations via Micrometer:
 * - `arc.rag.documents.chunked` — number of documents that were actually split
 * - `arc.rag.chunks.created` — total chunks produced
 * - `arc.rag.chunk.size.chars` — chunk size distribution (p50, p95)
 */
class InstrumentedDocumentChunker(
    private val delegate: DocumentChunker,
    registry: MeterRegistry
) : DocumentChunker {

    private val documentsChunked = registry.counter("arc.rag.documents.chunked")
    private val chunksCreated = registry.counter("arc.rag.chunks.created")
    private val chunkSize: DistributionSummary = DistributionSummary.builder("arc.rag.chunk.size.chars")
        .publishPercentiles(0.5, 0.95)
        .register(registry)

    override fun chunk(document: Document): List<Document> {
        val result = delegate.chunk(document)
        if (result.size > 1) {
            documentsChunked.increment()
            chunksCreated.increment(result.size.toDouble())
            result.forEach { chunkSize.record(it.text.orEmpty().length.toDouble()) }
        }
        return result
    }
}
