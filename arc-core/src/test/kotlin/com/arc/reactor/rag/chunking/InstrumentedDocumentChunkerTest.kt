package com.arc.reactor.rag.chunking

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

/**
 * InstrumentedDocumentChunker에 대한 테스트.
 *
 * 계측된 문서 청킹 동작을 검증합니다.
 */
class InstrumentedDocumentChunkerTest {

    private val registry = SimpleMeterRegistry()
    private val delegate = TokenBasedDocumentChunker()
    private val instrumented = InstrumentedDocumentChunker(delegate, registry)

    @Test
    fun `chunked은(는) document increments metrics`() {
        val content = buildString {
            val para = "This is a paragraph about artificial intelligence and machine learning. " +
                "It covers topics such as neural networks, deep learning, and natural language processing. " +
                "These technologies are transforming industries across the globe.\n\n"
            while (length < 6000) append(para)
        }
        val doc = Document("metric-doc", content, emptyMap())

        val result = instrumented.chunk(doc)

        val chunkedCount = registry.counter("arc.rag.documents.chunked").count()
        val createdCount = registry.counter("arc.rag.chunks.created").count()
        val sizeSummary = registry.find("arc.rag.chunk.size.chars").summary()

        assertEquals(1.0, chunkedCount, "documents.chunked should be incremented once")
        assertEquals(result.size.toDouble(), createdCount, "chunks.created should match chunk count")
        assertEquals(result.size.toLong(), sizeSummary?.count(), "chunk.size.chars should record each chunk")
    }

    @Test
    fun `짧은 document does not record metrics`() {
        val doc = Document("short-doc", "Short content", emptyMap())

        val result = instrumented.chunk(doc)

        assertEquals(1, result.size, "Short document should not be split")
        assertEquals(0.0, registry.counter("arc.rag.documents.chunked").count(),
            "documents.chunked should not increment for non-chunked documents")
        assertEquals(0.0, registry.counter("arc.rag.chunks.created").count(),
            "chunks.created should not increment for non-chunked documents")
        assertNull(registry.find("arc.rag.chunk.size.chars").summary()?.let {
            if (it.count() == 0L) null else it
        }, "chunk.size.chars should not record for non-chunked documents")
    }

    @Test
    fun `배치 processing records metrics for each chunked document`() {
        val longContent = buildString {
            val para = "This is a paragraph about artificial intelligence and machine learning. " +
                "It covers topics such as neural networks and deep learning.\n\n"
            while (length < 6000) append(para)
        }
        val docs = listOf(
            Document("short", "Short", emptyMap()),
            Document("long1", longContent, emptyMap()),
            Document("long2", longContent, emptyMap())
        )

        instrumented.chunk(docs)

        val chunkedCount = registry.counter("arc.rag.documents.chunked").count()
        assertEquals(2.0, chunkedCount, "Only the 2 long documents should be counted as chunked")
    }
}
