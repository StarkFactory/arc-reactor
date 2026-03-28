package com.arc.reactor.rag.impl

import com.arc.reactor.rag.chunking.DocumentChunker
import com.arc.reactor.rag.chunking.NoOpDocumentChunker
import com.arc.reactor.rag.model.RetrievedDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

/**
 * SimpleContextBuilder, PassthroughQueryTransformer, DocumentChunker
 * 유틸리티 메서드에 대한 단위 테스트.
 */
class SimpleContextBuilderTest {

    // ────────────────────────────────────────────────────────────────
    // SimpleContextBuilder
    // ────────────────────────────────────────────────────────────────

    @Nested
    inner class SimpleContextBuilderTests {

        private val builder = SimpleContextBuilder()

        @Test
        fun `빈 문서 목록이면 빈 문자열을 반환해야 한다`() {
            val result = builder.build(emptyList(), maxTokens = 1000)
            assertEquals("", result) { "문서가 없으면 빈 컨텍스트여야 합니다, 실제: '$result'" }
        }

        @Test
        fun `단일 문서의 번호와 내용이 포함되어야 한다`() {
            val doc = RetrievedDocument(id = "d1", content = "Kotlin은 JVM 언어다")
            val result = builder.build(listOf(doc), maxTokens = 1000)
            assertTrue(result.contains("[1]")) { "첫 번째 문서 번호 '[1]'이 있어야 합니다, 실제: '$result'" }
            assertTrue(result.contains("Kotlin은 JVM 언어다")) { "문서 내용이 포함되어야 합니다, 실제: '$result'" }
        }

        @Test
        fun `source가 있으면 Source 레이블이 포함되어야 한다`() {
            val doc = RetrievedDocument(id = "d1", content = "내용", source = "docs/kotlin.md")
            val result = builder.build(listOf(doc), maxTokens = 1000)
            assertTrue(result.contains("Source: docs/kotlin.md")) { "출처 정보가 포함되어야 합니다, 실제: '$result'" }
        }

        @Test
        fun `source가 없으면 Source 레이블이 없어야 한다`() {
            val doc = RetrievedDocument(id = "d1", content = "내용")
            val result = builder.build(listOf(doc), maxTokens = 1000)
            assertFalse(result.contains("Source:")) { "출처가 null이면 Source 레이블이 없어야 합니다, 실제: '$result'" }
        }

        @Test
        fun `여러 문서는 구분자와 순차 번호로 연결되어야 한다`() {
            val docs = listOf(
                RetrievedDocument(id = "a", content = "첫 번째 문서"),
                RetrievedDocument(id = "b", content = "두 번째 문서"),
            )
            val result = builder.build(docs, maxTokens = 5000)
            assertTrue(result.contains("[1]")) { "[1] 번호가 있어야 합니다, 실제: '$result'" }
            assertTrue(result.contains("[2]")) { "[2] 번호가 있어야 합니다, 실제: '$result'" }
            assertTrue(result.contains("---")) { "구분자가 있어야 합니다, 실제: '$result'" }
        }

        @Test
        fun `토큰 예산 초과 문서는 포함하지 않아야 한다`() {
            // estimatedTokens = content.length / 4
            // 400자 문서 = 100 토큰, maxTokens = 50 → 두 번째 문서만 제외
            val smallDoc = RetrievedDocument(id = "small", content = "A".repeat(40))   // 10 토큰
            val bigDoc = RetrievedDocument(id = "big", content = "B".repeat(400))       // 100 토큰
            val result = builder.build(listOf(smallDoc, bigDoc), maxTokens = 50)
            assertTrue(result.contains("small".let { "A".repeat(40) })) {
                "예산 내 첫 문서는 포함되어야 합니다, 실제: '$result'"
            }
            assertFalse(result.contains("B".repeat(400))) {
                "예산 초과 문서는 포함되지 않아야 합니다, 실제 길이: ${result.length}"
            }
        }

        @Test
        fun `커스텀 구분자를 사용할 수 있어야 한다`() {
            val customBuilder = SimpleContextBuilder(separator = "\n===\n")
            val docs = listOf(
                RetrievedDocument(id = "1", content = "문서 A"),
                RetrievedDocument(id = "2", content = "문서 B"),
            )
            val result = customBuilder.build(docs, maxTokens = 5000)
            assertTrue(result.contains("\n===\n")) { "커스텀 구분자가 사용되어야 합니다, 실제: '$result'" }
        }

        @Test
        fun `정확히 예산에 맞는 문서는 포함되어야 한다`() {
            // 100자 = 25 토큰, maxTokens = 25 → 딱 맞으면 포함 (currentTokens + docTokens > maxTokens 경계)
            val doc = RetrievedDocument(id = "exact", content = "X".repeat(100))
            val result = builder.build(listOf(doc), maxTokens = 25)
            assertTrue(result.contains("X".repeat(100))) {
                "토큰 예산과 정확히 일치하면 포함되어야 합니다 (> 조건), 실제: '$result'"
            }
        }

        @Test
        fun `예산보다 1토큰 초과하는 문서는 제외되어야 한다`() {
            // 104자 = 26 토큰, maxTokens = 25 → 초과 → 제외
            val doc = RetrievedDocument(id = "over", content = "X".repeat(104))
            val result = builder.build(listOf(doc), maxTokens = 25)
            assertEquals("", result) {
                "토큰 예산을 1토큰 초과하면 제외되어야 합니다 (off-by-one 검증), 실제: '$result'"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // PassthroughQueryTransformer
    // ────────────────────────────────────────────────────────────────

    @Nested
    inner class PassthroughQueryTransformerTests {

        private val transformer = PassthroughQueryTransformer()

        @Test
        fun `쿼리를 변환 없이 단일 원소 리스트로 반환해야 한다`() = runTest {
            val result = transformer.transform("Kotlin coroutines 설명해줘")
            assertEquals(1, result.size) { "반환 리스트 크기는 1이어야 합니다, 실제: ${result.size}" }
            assertEquals("Kotlin coroutines 설명해줘", result[0]) {
                "원본 쿼리가 그대로 반환되어야 합니다, 실제: '${result[0]}'"
            }
        }

        @Test
        fun `빈 쿼리도 그대로 반환해야 한다`() = runTest {
            val result = transformer.transform("")
            assertEquals(1, result.size) { "빈 쿼리도 단일 원소 리스트여야 합니다, 실제: ${result.size}" }
            assertEquals("", result[0]) { "빈 쿼리는 빈 문자열로 반환되어야 합니다, 실제: '${result[0]}'" }
        }

        @Test
        fun `공백만 있는 쿼리도 변환 없이 반환해야 한다`() = runTest {
            val result = transformer.transform("   ")
            assertEquals("   ", result[0]) { "공백 쿼리는 그대로 반환되어야 합니다, 실제: '${result[0]}'" }
        }

        @Test
        fun `특수문자가 포함된 쿼리도 변환하지 않아야 한다`() = runTest {
            val specialQuery = "SELECT * FROM docs WHERE id='1';"
            val result = transformer.transform(specialQuery)
            assertEquals(specialQuery, result[0]) {
                "특수문자 쿼리도 그대로 반환되어야 합니다, 실제: '${result[0]}'"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // DocumentChunker companion object 유틸리티
    // ────────────────────────────────────────────────────────────────

    @Nested
    inner class DocumentChunkerCompanionTests {

        @Test
        fun `chunkId는 동일한 입력에 대해 항상 같은 UUID를 반환해야 한다`() {
            val id1 = DocumentChunker.chunkId("parent-123", 0)
            val id2 = DocumentChunker.chunkId("parent-123", 0)
            assertEquals(id1, id2) { "동일한 parentId/index에 대해 결정적(deterministic) ID여야 합니다" }
        }

        @Test
        fun `chunkId는 인덱스가 다르면 다른 UUID를 반환해야 한다`() {
            val id0 = DocumentChunker.chunkId("parent-abc", 0)
            val id1 = DocumentChunker.chunkId("parent-abc", 1)
            assertFalse(id0 == id1) { "인덱스가 다르면 청크 ID가 달라야 합니다" }
        }

        @Test
        fun `chunkId는 부모 ID가 다르면 다른 UUID를 반환해야 한다`() {
            val idA = DocumentChunker.chunkId("parent-A", 0)
            val idB = DocumentChunker.chunkId("parent-B", 0)
            assertFalse(idA == idB) { "부모 ID가 다르면 청크 ID가 달라야 합니다" }
        }

        @Test
        fun `chunkId는 유효한 UUID 형식이어야 한다`() {
            val id = DocumentChunker.chunkId("doc-001", 3)
            val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
            assertTrue(uuidPattern.matches(id)) { "청크 ID가 UUID 형식이어야 합니다, 실제: '$id'" }
        }

        @Test
        fun `deriveChunkIds는 maxChunks 개수만큼 ID를 생성해야 한다`() {
            val ids = DocumentChunker.deriveChunkIds("doc-xyz", maxChunks = 5)
            assertEquals(5, ids.size) { "5개 청크 ID가 생성되어야 합니다, 실제: ${ids.size}" }
        }

        @Test
        fun `deriveChunkIds는 chunkId와 동일한 결정적 ID를 생성해야 한다`() {
            val parentId = "parent-99"
            val derived = DocumentChunker.deriveChunkIds(parentId, maxChunks = 3)
            for (i in 0 until 3) {
                assertEquals(
                    DocumentChunker.chunkId(parentId, i),
                    derived[i]
                ) { "deriveChunkIds[$i]가 chunkId($parentId, $i)와 같아야 합니다" }
            }
        }

        @Test
        fun `deriveChunkIds에 maxChunks=0이면 빈 리스트를 반환해야 한다`() {
            val ids = DocumentChunker.deriveChunkIds("doc-empty", maxChunks = 0)
            assertEquals(0, ids.size) { "maxChunks=0이면 빈 리스트여야 합니다, 실제 크기: ${ids.size}" }
        }

        @Test
        fun `isChunkId는 구분자를 포함한 ID를 청크 ID로 인식해야 한다`() {
            // DocumentChunker.chunkId 결과물이 아닌 임의 문자열은 구분자가 없으므로 false여야 함
            // 단, chunkId()는 UUID v3을 반환하므로 ":chunk:" 구분자가 없음 → isChunkId는 레거시 호환성 용도
            val legacyChunkId = "doc-parent:chunk:0"
            assertTrue(DocumentChunker.isChunkId(legacyChunkId)) {
                "':chunk:' 구분자가 포함된 ID는 청크 ID로 인식되어야 합니다, 실제: '$legacyChunkId'"
            }
        }

        @Test
        fun `isChunkId는 일반 UUID를 청크 ID로 인식하지 않아야 한다`() {
            val plainUuid = "550e8400-e29b-41d4-a716-446655440000"
            assertFalse(DocumentChunker.isChunkId(plainUuid)) {
                "순수 UUID는 청크 ID가 아니어야 합니다, 실제: '$plainUuid'"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // NoOpDocumentChunker
    // ────────────────────────────────────────────────────────────────

    @Nested
    inner class NoOpDocumentChunkerTests {

        private val chunker = NoOpDocumentChunker()

        @Test
        fun `단일 문서를 그대로 단일 원소 리스트로 반환해야 한다`() {
            val doc = Document("noop-1", "내용 없음", emptyMap())
            val result = chunker.chunk(doc)
            assertEquals(1, result.size) { "NoOp은 항상 1개 문서를 반환해야 합니다, 실제: ${result.size}" }
            assertEquals("noop-1", result[0].id) { "원본 문서 ID가 보존되어야 합니다" }
        }

        @Test
        fun `여러 문서는 각각 청킹되어 동일한 수로 반환되어야 한다`() {
            val docs = listOf(
                Document("a", "문서 A", emptyMap()),
                Document("b", "문서 B", emptyMap()),
                Document("c", "문서 C", emptyMap()),
            )
            val result = chunker.chunk(docs)
            assertEquals(3, result.size) { "NoOp은 입력과 동일한 수를 반환해야 합니다, 실제: ${result.size}" }
        }

        @Test
        fun `빈 문서 목록에 대해 빈 리스트를 반환해야 한다`() {
            val result = chunker.chunk(emptyList<Document>())
            assertTrue(result.isEmpty()) { "빈 입력에 대해 빈 리스트여야 합니다, 실제 크기: ${result.size}" }
        }

        @Test
        fun `문서 내용이 변경되지 않아야 한다`() {
            val content = "변경되지 않아야 할 텍스트 내용"
            val doc = Document("id-x", content, mapOf("key" to "value"))
            val result = chunker.chunk(doc)
            assertEquals(content, result[0].text.orEmpty()) {
                "NoOp 청커는 내용을 변경하지 않아야 합니다, 실제: '${result[0].text}'"
            }
        }
    }
}
