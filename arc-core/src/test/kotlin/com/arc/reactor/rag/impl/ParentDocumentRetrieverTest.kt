package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.chunking.DocumentChunker
import com.arc.reactor.rag.model.RetrievedDocument
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * ParentDocumentRetriever에 대한 테스트.
 *
 * 상위 문서 조회 전략의 동작을 검증합니다.
 */
class ParentDocumentRetrieverTest {

    private val delegate = mockk<DocumentRetriever>()

    private fun retriever(windowSize: Int = 1) =
        ParentDocumentRetriever(delegate = delegate, windowSize = windowSize)

    // -- 헬퍼 --

    private fun chunkedDoc(
        parentId: String,
        index: Int,
        total: Int,
        content: String,
        score: Double = 0.9
    ): RetrievedDocument {
        val id = DocumentChunker.chunkId(parentId, index)
        return RetrievedDocument(
            id = id,
            content = content,
            metadata = mapOf(
                "parent_document_id" to parentId,
                "chunk_index" to index,
                "chunk_total" to total,
                "chunked" to true
            ),
            score = score
        )
    }

    private fun plainDoc(
        id: String,
        content: String,
        score: Double = 0.85
    ): RetrievedDocument = RetrievedDocument(
        id = id,
        content = content,
        score = score
    )

    // -- 테스트 --

    @Test
    fun `pass through non-chunked documents unchanged해야 한다`() = runTest {
        val docs = listOf(plainDoc("d1", "hello"), plainDoc("d2", "world"))
        coEvery { delegate.retrieve(any(), any(), any()) } returns docs

        val result = retriever().retrieve(listOf("query"), 10)

        result shouldHaveSize 2
        result[0].id shouldBe "d1"
        result[1].id shouldBe "d2"
    }

    @Test
    fun `delegate returns nothing일 때 return empty list해야 한다`() = runTest {
        coEvery { delegate.retrieve(any(), any(), any()) } returns emptyList()

        val result = retriever().retrieve(listOf("query"), 10)

        result shouldHaveSize 0
    }

    @Test
    fun `merge adjacent chunks from same parent해야 한다`() = runTest {
        val parentId = "parent-1"
        val chunk0 = chunkedDoc(parentId, 0, 3, "first chunk", score = 0.7)
        val chunk1 = chunkedDoc(parentId, 1, 3, "second chunk", score = 0.9)
        val chunk2 = chunkedDoc(parentId, 2, 3, "third chunk", score = 0.6)

        // returns chunks 0, 1, 2 (all within window of each other) 위임
        coEvery { delegate.retrieve(any(), any(), any()) } returns listOf(chunk1, chunk0, chunk2)

        val result = retriever(windowSize = 1).retrieve(listOf("query"), 10)

        result shouldHaveSize 1
        result[0].id shouldBe parentId
        result[0].content shouldContain "first chunk"
        result[0].content shouldContain "second chunk"
        result[0].content shouldContain "third chunk"
        result[0].score shouldBe 0.9
    }

    @Test
    fun `preserve chunk order in merged content해야 한다`() = runTest {
        val parentId = "doc-abc"
        val c0 = chunkedDoc(parentId, 0, 3, "AAA")
        val c1 = chunkedDoc(parentId, 1, 3, "BBB")
        val c2 = chunkedDoc(parentId, 2, 3, "CCC")

        coEvery { delegate.retrieve(any(), any(), any()) } returns listOf(c2, c0, c1)

        val result = retriever(windowSize = 2).retrieve(listOf("q"), 10)

        result shouldHaveSize 1
        val parts = result[0].content.split("\n")
        parts[0] shouldBe "AAA"
        parts[1] shouldBe "BBB"
        parts[2] shouldBe "CCC"
    }

    @Test
    fun `handle mix of chunked and non-chunked documents해야 한다`() = runTest {
        val parentId = "parent-2"
        val chunk = chunkedDoc(parentId, 0, 2, "chunked content", score = 0.8)
        val plain = plainDoc("standalone", "plain content", score = 0.95)

        coEvery { delegate.retrieve(any(), any(), any()) } returns listOf(plain, chunk)

        val result = retriever().retrieve(listOf("query"), 10)

        result shouldHaveSize 2
        // plain doc has higher score,은(는) come first해야 합니다
        result[0].id shouldBe "standalone"
        result[1].id shouldBe parentId
    }

    @Test
    fun `deduplicate overlapping windows from same parent해야 한다`() = runTest {
        val parentId = "parent-3"
        // chunks 1 and 2 both in results; with window=1, their windows overlap
        val c1 = chunkedDoc(parentId, 1, 5, "chunk-1", score = 0.9)
        val c2 = chunkedDoc(parentId, 2, 5, "chunk-2", score = 0.85)

        coEvery { delegate.retrieve(any(), any(), any()) } returns listOf(c1, c2)

        val result = retriever(windowSize = 1).retrieve(listOf("q"), 10)

        // produce a single merged document for the parent해야 합니다
        result shouldHaveSize 1
        result[0].id shouldBe parentId
        result[0].content shouldContain "chunk-1"
        result[0].content shouldContain "chunk-2"
    }

    @Test
    fun `handle multiple parent documents separately해야 한다`() = runTest {
        val c1 = chunkedDoc("parent-A", 0, 2, "A-chunk-0", score = 0.9)
        val c2 = chunkedDoc("parent-B", 1, 3, "B-chunk-1", score = 0.8)

        coEvery { delegate.retrieve(any(), any(), any()) } returns listOf(c1, c2)

        val result = retriever(windowSize = 0).retrieve(listOf("q"), 10)

        result shouldHaveSize 2
        val ids = result.map { it.id }.toSet()
        ids shouldBe setOf("parent-A", "parent-B")
    }

    @Test
    fun `expansion 후 respect topK limit해야 한다`() = runTest {
        val docs = (0 until 5).map { i ->
            chunkedDoc("parent-$i", 0, 1, "content-$i", score = 0.9 - i * 0.1)
        }
        coEvery { delegate.retrieve(any(), any(), any()) } returns docs

        val result = retriever().retrieve(listOf("q"), 3)

        result shouldHaveSize 3
    }

    @Test
    fun `use best score from chunk group해야 한다`() = runTest {
        val parentId = "scored-parent"
        val c0 = chunkedDoc(parentId, 0, 3, "low", score = 0.5)
        val c1 = chunkedDoc(parentId, 1, 3, "high", score = 0.95)
        val c2 = chunkedDoc(parentId, 2, 3, "mid", score = 0.7)

        coEvery { delegate.retrieve(any(), any(), any()) } returns listOf(c0, c1, c2)

        val result = retriever(windowSize = 1).retrieve(listOf("q"), 10)

        result shouldHaveSize 1
        result[0].score shouldBe 0.95
    }

    @Test
    fun `chunk indices로 include merged metadata해야 한다`() = runTest {
        val parentId = "meta-parent"
        val c0 = chunkedDoc(parentId, 0, 2, "A", score = 0.9)
        val c1 = chunkedDoc(parentId, 1, 2, "B", score = 0.8)

        coEvery { delegate.retrieve(any(), any(), any()) } returns listOf(c0, c1)

        val result = retriever(windowSize = 1).retrieve(listOf("q"), 10)

        result shouldHaveSize 1
        val meta = result[0].metadata
        meta["merged_chunks"] shouldBe 2
        meta["window_size"] shouldBe 1
        meta["chunk_indices"] shouldBe "0,1"
    }

    @Test
    fun `windowSize zero은(는) still merge same-parent hits해야 한다`() = runTest {
        val parentId = "zero-win"
        val c0 = chunkedDoc(parentId, 0, 3, "X", score = 0.9)
        val c2 = chunkedDoc(parentId, 2, 3, "Z", score = 0.8)

        coEvery { delegate.retrieve(any(), any(), any()) } returns listOf(c0, c2)

        val result = retriever(windowSize = 0).retrieve(listOf("q"), 10)

        // windowSize=0이더라도 동일 부모의 청크는 병합됨
        result shouldHaveSize 1
        result[0].id shouldBe parentId
        result[0].content shouldContain "X"
        result[0].content shouldContain "Z"
    }

    @Test
    fun `forward filters to delegate해야 한다`() = runTest {
        val filters = mapOf("category" to "docs")
        coEvery { delegate.retrieve(any(), any(), eq(filters)) } returns emptyList()

        val result = retriever().retrieve(listOf("q"), 5, filters)

        result shouldHaveSize 0
    }
}
