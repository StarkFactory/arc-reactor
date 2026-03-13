package com.arc.reactor.rag.chunking

import com.arc.reactor.memory.DefaultTokenEstimator
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.ai.document.Document

class TokenBasedDocumentChunkerTest {

    private val chunker = TokenBasedDocumentChunker(
        chunkSize = 512,
        minChunkSizeChars = 350,
        minChunkThreshold = 512,
        overlap = 50,
        keepSeparator = true,
        maxNumChunks = 100
    )

    @Nested
    inner class ShortDocuments {

        @Test
        fun `short document below threshold returns single document unchanged`() {
            val content = "Q: What is Kotlin?\n\nA: Kotlin is a modern programming language."
            val doc = Document("doc-1", content, mapOf("source" to "test"))

            val result = chunker.chunk(doc)

            assertEquals(1, result.size, "Short document should not be split")
            assertEquals("doc-1", result[0].id, "Original document ID should be preserved")
            assertEquals(content, result[0].text.orEmpty(), "Content should be unchanged")
        }

        @Test
        fun `document at exact threshold boundary is not split`() {
            // DefaultTokenEstimator: Latin chars ~4 chars/token → 2048 chars ≈ 512 tokens = threshold
            val content = "x".repeat(2048)
            val doc = Document("boundary-doc", content, emptyMap())

            val result = chunker.chunk(doc)

            assertEquals(1, result.size, "Document at threshold should not be split")
        }

        @Test
        fun `blank document returns unchanged`() {
            val doc = Document("blank-doc", "   ", emptyMap())

            val result = chunker.chunk(doc)

            assertEquals(1, result.size, "Blank document should return as-is")
        }
    }

    @Nested
    inner class LongDocuments {

        @Test
        fun `long document is split into multiple chunks`() {
            // Create a document well above threshold (~1500 tokens = ~6000 chars)
            val content = buildLongContent(6000)
            val doc = Document("long-doc", content, mapOf("source" to "test"))

            val result = chunker.chunk(doc)

            assertTrue(result.size > 1, "Long document should be split into multiple chunks, got: ${result.size}")
            result.forEach { chunk ->
                assertTrue(chunk.text.orEmpty().isNotBlank(), "Each chunk should have non-blank content")
            }
        }

        @Test
        fun `very long document produces many chunks`() {
            // ~10000 tokens = ~40000 chars
            val content = buildLongContent(40000)
            val doc = Document("very-long", content, emptyMap())

            val result = chunker.chunk(doc)

            assertTrue(
                result.size > 5,
                "40K char document should produce multiple chunks, got: ${result.size}"
            )
        }
    }

    @Nested
    inner class MetadataPropagation {

        @Test
        fun `original metadata is copied to all chunks`() {
            val metadata = mapOf<String, Any>(
                "source" to "rag_ingestion_candidate",
                "runId" to "run-123",
                "userId" to "user-456"
            )
            val content = buildLongContent(6000)
            val doc = Document("meta-doc", content, metadata)

            val result = chunker.chunk(doc)

            assertTrue(result.size > 1, "Expected multiple chunks")
            result.forEach { chunk ->
                assertEquals(
                    "rag_ingestion_candidate", chunk.metadata["source"],
                    "Source metadata should be propagated"
                )
                assertEquals("run-123", chunk.metadata["runId"], "runId should be propagated")
                assertEquals("user-456", chunk.metadata["userId"], "userId should be propagated")
            }
        }

        @Test
        fun `chunking metadata is added to all chunks`() {
            val content = buildLongContent(6000)
            val doc = Document("chunk-meta", content, emptyMap())

            val result = chunker.chunk(doc)

            assertTrue(result.size > 1, "Expected multiple chunks")
            result.forEachIndexed { index, chunk ->
                assertEquals(
                    "chunk-meta", chunk.metadata["parent_document_id"],
                    "parent_document_id should reference original doc"
                )
                assertEquals(index, chunk.metadata["chunk_index"], "chunk_index should match position")
                assertEquals(
                    result.size, chunk.metadata["chunk_total"],
                    "chunk_total should match total chunks"
                )
                assertEquals(true, chunk.metadata["chunked"], "chunked flag should be true")
            }
        }

        @Test
        fun `chunk IDs are unique and different from parent`() {
            val content = buildLongContent(6000)
            val doc = Document("parent-id", content, emptyMap())

            val result = chunker.chunk(doc)

            val ids = result.map { it.id }.toSet()
            assertEquals(result.size, ids.size, "All chunk IDs should be unique")
            assertFalse(ids.contains("parent-id"), "Chunk IDs should differ from parent ID")
        }
    }

    @Nested
    inner class QaFormat {

        @Test
        fun `short QA format document is not split`() {
            val content = "Q: How do I reset my password?\n\nA: Go to Settings > Security > Reset Password."
            val doc = Document("qa-short", content, mapOf("source" to "rag_ingestion_candidate"))

            val result = chunker.chunk(doc)

            assertEquals(1, result.size, "Short Q&A should not be split")
        }

        @Test
        fun `long QA format document is split correctly`() {
            val longAnswer = "A: " + "This is a detailed explanation. ".repeat(200)
            val content = "Q: Explain RAG architecture in detail?\n\n$longAnswer"
            val doc = Document("qa-long", content, mapOf("source" to "rag_ingestion_candidate"))

            val result = chunker.chunk(doc)

            assertTrue(result.size > 1, "Long Q&A should be split, got: ${result.size}")
        }
    }

    @Nested
    inner class NoOpChunker {

        @Test
        fun `NoOpDocumentChunker always returns original document`() {
            val noop = NoOpDocumentChunker()
            val content = buildLongContent(10000)
            val doc = Document("noop-doc", content, mapOf("key" to "value"))

            val result = noop.chunk(doc)

            assertEquals(1, result.size, "NoOp should always return 1 document")
            assertEquals("noop-doc", result[0].id, "Should return the original document")
        }
    }

    @Nested
    inner class BatchProcessing {

        @Test
        fun `batch processing chunks each document independently`() {
            val shortDoc = Document("short", "Short content", emptyMap())
            val longDoc = Document("long", buildLongContent(6000), emptyMap())

            val result = chunker.chunk(listOf(shortDoc, longDoc))

            assertTrue(result.size > 2, "Batch should produce more documents than input")
            // First result is the short doc (unchanged)
            assertEquals("short", result[0].id, "Short doc should be first and unchanged")
            // Remaining are chunks from the long doc
            val longChunks = result.drop(1)
            assertTrue(longChunks.all { it.metadata["parent_document_id"] == "long" },
                "Long doc chunks should reference parent")
        }
    }

    @Nested
    inner class OverlapVerification {

        @Test
        fun `adjacent chunks have overlapping content`() {
            // Use content with distinct sentences for overlap detection
            val sentences = (1..100).map { "Sentence number $it contains unique information. " }
            val content = sentences.joinToString("")
            val doc = Document("overlap-doc", content, emptyMap())

            val result = chunker.chunk(doc)

            if (result.size >= 2) {
                // Check that some content from end of chunk N appears in start of chunk N+1
                for (i in 0 until result.size - 1) {
                    val currentEnd = result[i].text.orEmpty().takeLast(100)
                    val nextStart = result[i + 1].text.orEmpty().take(300)
                    // With overlap, the end of current chunk should share some words with next
                    val currentWords = currentEnd.split("\\s+".toRegex()).filter { it.length > 3 }
                    val nextWords = nextStart.split("\\s+".toRegex()).toSet()
                    val overlap = currentWords.count { it in nextWords }
                    assertTrue(
                        overlap > 0,
                        "Adjacent chunks $i and ${i + 1} should have overlapping content"
                    )
                }
            }
        }
    }

    @Nested
    inner class MaxChunksLimit {

        @Test
        fun `chunks are limited to maxNumChunks`() {
            val limitedChunker = TokenBasedDocumentChunker(
                chunkSize = 50,       // Very small chunks
                minChunkSizeChars = 10,
                minChunkThreshold = 50,
                overlap = 5,
                maxNumChunks = 5
            )
            val content = buildLongContent(10000)
            val doc = Document("max-chunks", content, emptyMap())

            val result = limitedChunker.chunk(doc)

            assertTrue(
                result.size <= 5,
                "Should not exceed maxNumChunks=5, got: ${result.size}"
            )
        }
    }

    @Nested
    inner class CjkTokenEstimation {

        @Test
        fun `korean document is chunked with accurate token estimation`() {
            // CJK chars: ~1.5 chars/token → 3000 Korean chars ≈ 2000 tokens (well above 512 threshold)
            val koreanParagraph = "인공지능과 머신러닝은 현대 기술의 핵심입니다. " +
                "딥러닝과 자연어 처리 기술은 전 세계 산업을 변화시키고 있습니다.\n\n"
            val content = buildString {
                while (length < 3000) append(koreanParagraph)
            }.take(3000)
            val doc = Document("korean-doc", content, emptyMap())

            val result = chunker.chunk(doc)

            assertTrue(result.size > 1, "Korean document with ~2000 tokens should be chunked, got: ${result.size}")
            result.forEach { chunk ->
                assertTrue(chunk.text.orEmpty().isNotBlank(), "Each chunk should have non-blank content")
            }
        }

        @Test
        fun `short korean document below threshold is not split`() {
            // ~200 Korean chars ≈ ~133 tokens (below 512 threshold)
            val content = "한국어 짧은 문서입니다. ".repeat(10)
            val doc = Document("korean-short", content, emptyMap())

            val result = chunker.chunk(doc)

            assertEquals(1, result.size, "Short Korean document should not be split")
        }

        @Test
        fun `mixed language document uses dynamic char-per-token ratio`() {
            // Mix of English and Korean — ratio adapts per document
            val mixed = buildString {
                repeat(50) {
                    append("English text for testing purposes. ")
                    append("한국어 텍스트 테스트입니다. ")
                }
            }
            val doc = Document("mixed-doc", mixed, emptyMap())
            val estimator = DefaultTokenEstimator()
            val tokens = estimator.estimate(mixed)

            // If tokens exceed threshold, it should be chunked
            if (tokens > 512) {
                val result = chunker.chunk(doc)
                assertTrue(result.size > 1, "Mixed-language document with $tokens tokens should be chunked")
            }
        }
    }

    @Nested
    inner class ChunkIdHelpers {

        @Test
        fun `chunkId produces valid UUID strings`() {
            val chunkId = DocumentChunker.chunkId("parent-123", 0)
            val parsed = java.util.UUID.fromString(chunkId)
            assertEquals(chunkId, parsed.toString(), "chunkId should produce a valid UUID")
        }

        @Test
        fun `chunkId is deterministic`() {
            val id1 = DocumentChunker.chunkId("parent-123", 0)
            val id2 = DocumentChunker.chunkId("parent-123", 0)
            assertEquals(id1, id2, "Same parent + index should produce the same chunk ID")
        }

        @Test
        fun `chunkId varies by index`() {
            val id0 = DocumentChunker.chunkId("parent-123", 0)
            val id1 = DocumentChunker.chunkId("parent-123", 1)
            assertNotEquals(id0, id1, "Different indices should produce different chunk IDs")
        }

        @Test
        fun `isChunkId returns true for legacy chunk IDs`() {
            assertTrue(DocumentChunker.isChunkId("parent-123:chunk:0"), "Legacy chunk ID should be recognized")
        }

        @Test
        fun `isChunkId returns false for parent IDs`() {
            assertFalse(DocumentChunker.isChunkId("parent-123"), "Plain ID should not be a chunk ID")
            assertFalse(DocumentChunker.isChunkId("uuid-abc-def"), "UUID should not be a chunk ID")
        }
    }

    private fun buildLongContent(targetLength: Int): String {
        val paragraph = "This is a paragraph about artificial intelligence and machine learning. " +
            "It covers topics such as neural networks, deep learning, and natural language processing. " +
            "These technologies are transforming industries across the globe.\n\n"
        return buildString {
            while (length < targetLength) {
                append(paragraph)
            }
        }.take(targetLength)
    }
}
