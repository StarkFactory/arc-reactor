package com.arc.reactor.rag

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.cache.CacheKeyBuilder
import com.arc.reactor.rag.impl.DefaultRagPipeline
import com.arc.reactor.rag.impl.InMemoryDocumentRetriever
import com.arc.reactor.rag.impl.SpringAiVectorStoreRetriever
import com.arc.reactor.rag.ingestion.InMemoryRagIngestionPolicyStore
import com.arc.reactor.rag.ingestion.RagIngestionCandidate
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStatus
import com.arc.reactor.rag.ingestion.RagIngestionPolicy
import com.arc.reactor.rag.ingestion.toDocument
import com.arc.reactor.rag.ingestion.toDocuments
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.rag.search.RrfFusion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import java.time.Instant

/**
 * RAG + Cache 커버리지 보강 테스트.
 *
 * 기존 테스트 파일이 다루지 않는 영역을 검증한다:
 * - RrfFusion: 커스텀 K값, 단일 목록 중복 문서, 대형 랭크 정밀도
 * - CacheKeyBuilder: slackUserEmail/userEmail 신원 우선순위, 공백 userId 처리
 * - SpringAiVectorStoreRetriever: 다중 쿼리 중복 제거, PGVector 거리 변환, 점수 폴백
 * - DefaultRagPipeline: 쿼리 변환기 경로, 압축기 빈 결과 → EMPTY
 * - RagIngestionDocumentSupport.toDocument/toDocuments: 전체 미테스트 영역
 * - InMemoryRagIngestionPolicyStore: delete, 초기값, createdAt 보존
 */
class RagCacheGapTest {

    // ──────────────────────────────────────────────────────────────────
    // 1. RrfFusion — 기존 테스트에서 누락된 케이스
    // ──────────────────────────────────────────────────────────────────

    @Nested
    inner class RrfFusionGaps {

        @Test
        fun `커스텀 k 값이 스코어 계산에 반영된다`() {
            // k=1이면 rank-0 문서의 점수 = weight/(1+1) = 0.5/2 = 0.25
            val result = RrfFusion.fuse(
                vectorResults = listOf("doc-a" to 1.0),
                bm25Results = emptyList(),
                vectorWeight = 0.5,
                k = 1.0
            )

            assertEquals(1, result.size) { "단일 결과 크기가 1이어야 한다" }
            val expected = 0.5 / (1.0 + 1)
            assertEquals(expected, result[0].second, 0.000001) {
                "커스텀 k=1.0 에서 스코어는 $expected 이어야 한다, 실제: ${result[0].second}"
            }
        }

        @Test
        fun `k=60 기본값일 때 rank-0과 rank-1 스코어 차이가 명확하다`() {
            val result = RrfFusion.fuse(
                vectorResults = listOf("first" to 1.0, "second" to 0.5),
                bm25Results = emptyList(),
                vectorWeight = 1.0
            )

            val score0 = result.find { it.first == "first" }!!.second
            val score1 = result.find { it.first == "second" }!!.second
            assertTrue(score0 > score1) {
                "rank-0 문서가 rank-1 문서보다 점수가 높아야 한다: first=$score0, second=$score1"
            }
        }

        @Test
        fun `동일 문서가 두 목록에 모두 등장하면 점수가 합산된다`() {
            val shared = "shared-doc"
            // vector rank-0: 0.5/(60+1) = 0.008197
            // bm25 rank-0:   0.5/(60+1) = 0.008197
            // 합계: ≈ 0.016393
            val result = RrfFusion.fuse(
                vectorResults = listOf(shared to 0.9),
                bm25Results = listOf(shared to 8.0),
                vectorWeight = 0.5,
                bm25Weight = 0.5
            )

            assertEquals(1, result.size) { "동일 문서가 중복 없이 1개로 통합되어야 한다" }
            val expectedSingle = 0.5 / (RrfFusion.K + 1)
            val expectedCombined = expectedSingle * 2
            assertEquals(expectedCombined, result[0].second, 0.000001) {
                "두 목록의 점수가 합산되어야 한다: expected=$expectedCombined, actual=${result[0].second}"
            }
        }

        @Test
        fun `100개 문서 목록을 올바르게 융합하고 내림차순 정렬한다`() {
            val vector = (1..100).map { "doc-$it" to (100 - it).toDouble() }
            val bm25 = (51..100).map { "doc-$it" to (50 - (it - 51)).toDouble() }

            val result = RrfFusion.fuse(vector, bm25)

            // 결과 크기 = 100 (중복 없이)
            assertEquals(100, result.size) { "100개 문서가 모두 포함되어야 한다" }
            // 내림차순 정렬 확인
            for (i in 0 until result.size - 1) {
                assertTrue(result[i].second >= result[i + 1].second) {
                    "결과가 내림차순 정렬되어야 한다: rank $i=${result[i].second}, rank ${i + 1}=${result[i + 1].second}"
                }
            }
        }

        @Test
        fun `가중치 합이 1_0이 아닌 경우에도 올바르게 동작한다`() {
            val vector = listOf("a" to 1.0)
            val bm25 = listOf("b" to 1.0)

            // 가중치 합 = 0.3 + 0.3 = 0.6
            val result = RrfFusion.fuse(
                vectorResults = vector,
                bm25Results = bm25,
                vectorWeight = 0.3,
                bm25Weight = 0.3
            )

            assertEquals(2, result.size) { "두 문서가 모두 반환되어야 한다" }
            val scoreA = result.find { it.first == "a" }!!.second
            val scoreB = result.find { it.first == "b" }!!.second
            val expected = 0.3 / (RrfFusion.K + 1)
            assertEquals(expected, scoreA, 0.000001) {
                "벡터 전용 문서 스코어가 0.3/(K+1)이어야 한다"
            }
            assertEquals(expected, scoreB, 0.000001) {
                "BM25 전용 문서 스코어가 0.3/(K+1)이어야 한다"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. CacheKeyBuilder — 신원 우선순위 및 경계 케이스
    // ──────────────────────────────────────────────────────────────────

    @Nested
    inner class CacheKeyBuilderGaps {

        @Test
        fun `requesterEmail이 있으면 slackUserEmail이 달라도 requesterEmail만 신원 스코프에 반영된다`() {
            // withBoth: requesterEmail=alice, slackUserEmail=bob → 신원 = alice
            // withDifferentSlack: requesterEmail=alice, slackUserEmail=charlie → 신원 = alice
            // 두 핑거프린트는 같아야 한다 (slackUserEmail이 무시되므로)
            val withBob = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "hello",
                metadata = mapOf(
                    "requesterEmail" to "alice@example.com",
                    "slackUserEmail" to "bob@example.com"
                )
            )
            val withCharlie = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "hello",
                metadata = mapOf(
                    "requesterEmail" to "alice@example.com",
                    "slackUserEmail" to "charlie@example.com"
                )
            )

            val keyBob = CacheKeyBuilder.buildScopeFingerprint(withBob, emptyList())
            val keyCharlie = CacheKeyBuilder.buildScopeFingerprint(withCharlie, emptyList())

            assertEquals(keyBob, keyCharlie) {
                "requesterEmail이 있으면 slackUserEmail 값에 관계없이 동일한 핑거프린트여야 한다"
            }
        }

        @Test
        fun `slackUserEmail만 있으면 신원 스코프에 포함된다`() {
            val cmd1 = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "hello",
                metadata = mapOf("slackUserEmail" to "alice@example.com")
            )
            val cmd2 = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "hello",
                metadata = mapOf("slackUserEmail" to "bob@example.com")
            )

            val key1 = CacheKeyBuilder.buildScopeFingerprint(cmd1, emptyList())
            val key2 = CacheKeyBuilder.buildScopeFingerprint(cmd2, emptyList())

            assertNotEquals(key1, key2) {
                "다른 slackUserEmail은 다른 스코프 핑거프린트를 생성해야 한다"
            }
        }

        @Test
        fun `userEmail만 있으면 신원 스코프에 포함된다`() {
            val cmd1 = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "query",
                metadata = mapOf("userEmail" to "alice@example.com")
            )
            val cmd2 = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "query",
                metadata = mapOf("userEmail" to "bob@example.com")
            )

            assertNotEquals(
                CacheKeyBuilder.buildScopeFingerprint(cmd1, emptyList()),
                CacheKeyBuilder.buildScopeFingerprint(cmd2, emptyList())
            ) { "다른 userEmail은 다른 스코프 핑거프린트를 생성해야 한다" }
        }

        @Test
        fun `공백 requesterEmail은 신원으로 사용되지 않고 다음 후보로 대체된다`() {
            // 공백 requesterEmail → userEmail 사용
            val cmdWithBlankEmail = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "query",
                metadata = mapOf(
                    "requesterEmail" to "  ",  // 공백 → 무시
                    "userEmail" to "alice@example.com"
                )
            )
            val cmdWithUserEmail = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "query",
                metadata = mapOf("userEmail" to "alice@example.com")
            )

            assertEquals(
                CacheKeyBuilder.buildScopeFingerprint(cmdWithBlankEmail, emptyList()),
                CacheKeyBuilder.buildScopeFingerprint(cmdWithUserEmail, emptyList())
            ) { "공백 requesterEmail은 건너뛰고 userEmail이 신원으로 사용되어야 한다" }
        }

        @Test
        fun `신원 메타데이터가 전혀 없으면 공백 신원으로 처리된다`() {
            val cmdNoIdentity = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "query",
                metadata = emptyMap()
            )
            val cmdEmptyIdentity = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "query",
                metadata = mapOf("otherKey" to "value")
            )

            assertEquals(
                CacheKeyBuilder.buildScopeFingerprint(cmdNoIdentity, emptyList()),
                CacheKeyBuilder.buildScopeFingerprint(cmdEmptyIdentity, emptyList())
            ) { "알 수 없는 메타데이터 키는 신원 스코프에 영향을 주지 않아야 한다" }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. SpringAiVectorStoreRetriever — 기존 테스트에서 누락된 케이스
    // ──────────────────────────────────────────────────────────────────

    @Nested
    inner class SpringAiVectorStoreRetrieverGaps {

        @Test
        fun `다중 쿼리 결과에서 중복 문서는 점수가 높은 버전만 유지한다`() = runBlocking {
            val vectorStore = mockk<VectorStore>()
            // 동일 ID "doc-1"이 두 쿼리에서 각각 다른 점수로 반환됨
            every { vectorStore.similaritySearch(any<SearchRequest>()) } returnsMany listOf(
                listOf(
                    Document.builder().id("doc-1").text("내용1").metadata("score", 0.9).build()
                ),
                listOf(
                    Document.builder().id("doc-1").text("내용1").metadata("score", 0.7).build(),
                    Document.builder().id("doc-2").text("내용2").metadata("score", 0.8).build()
                )
            )

            val retriever = SpringAiVectorStoreRetriever(vectorStore, timeoutMs = 5000)

            val results = retriever.retrieve(
                queries = listOf("첫 번째 쿼리", "두 번째 쿼리"),
                topK = 10
            )

            val doc1Results = results.filter { it.id == "doc-1" }
            assertEquals(1, doc1Results.size) {
                "중복 ID는 1개만 포함되어야 한다, 실제: ${doc1Results.size}개"
            }
            // 점수 0.9(score 메타데이터 → score)가 더 높으므로 0.9로 유지
            assertEquals(0.9, doc1Results[0].score, 0.001) {
                "중복 중 더 높은 점수가 보존되어야 한다, 실제: ${doc1Results[0].score}"
            }
        }

        @Test
        fun `PGVector distance 메타데이터가 있으면 1 빼기 distance로 점수를 계산한다`() = runBlocking {
            val vectorStore = mockk<VectorStore>()
            // PGVector는 distance(낮을수록 좋음)를 반환
            every { vectorStore.similaritySearch(any<SearchRequest>()) } returns listOf(
                Document.builder().id("pgvec-doc").text("PGVector 문서").metadata("distance", 0.2).build()
            )

            val retriever = SpringAiVectorStoreRetriever(vectorStore, timeoutMs = 5000)

            val results = retriever.retrieve(listOf("쿼리"), topK = 5)

            assertEquals(1, results.size) { "결과가 1개여야 한다" }
            assertEquals(0.8, results[0].score, 0.001) {
                "distance=0.2이면 점수는 1-0.2=0.8이어야 한다, 실제: ${results[0].score}"
            }
        }

        @Test
        fun `distance가 없고 score도 없으면 점수 0_0을 반환한다`() = runBlocking {
            val vectorStore = mockk<VectorStore>()
            // 메타데이터가 비어있음
            every { vectorStore.similaritySearch(any<SearchRequest>()) } returns listOf(
                Document.builder().id("no-score-doc").text("메타데이터 없음").build()
            )

            val retriever = SpringAiVectorStoreRetriever(vectorStore, timeoutMs = 5000)

            val results = retriever.retrieve(listOf("쿼리"), topK = 5)

            assertEquals(1, results.size) { "결과가 1개여야 한다" }
            assertEquals(0.0, results[0].score, 0.001) {
                "메타데이터가 없으면 점수 0.0이어야 한다, 실제: ${results[0].score}"
            }
        }

        @Test
        fun `distance가 1_0을 초과하면 점수를 0_0으로 클램프한다`() = runBlocking {
            val vectorStore = mockk<VectorStore>()
            every { vectorStore.similaritySearch(any<SearchRequest>()) } returns listOf(
                Document.builder().id("d").text("내용").metadata("distance", 1.5).build()
            )

            val retriever = SpringAiVectorStoreRetriever(vectorStore, timeoutMs = 5000)

            val results = retriever.retrieve(listOf("쿼리"), topK = 5)

            assertTrue(results[0].score >= 0.0) {
                "distance=1.5이면 1-1.5=-0.5를 coerceIn(0,1)으로 0.0이어야 한다, 실제: ${results[0].score}"
            }
            assertEquals(0.0, results[0].score, 0.001) {
                "distance > 1.0이면 점수는 0.0으로 클램프되어야 한다"
            }
        }

        @Test
        fun `source 메타데이터가 있으면 RetrievedDocument의 source 필드에 매핑된다`() = runBlocking {
            val vectorStore = mockk<VectorStore>()
            every { vectorStore.similaritySearch(any<SearchRequest>()) } returns listOf(
                Document.builder()
                    .id("src-doc")
                    .text("출처 있는 문서")
                    .metadata("source", "docs/manual.md")
                    .metadata("score", 0.85)
                    .build()
            )

            val retriever = SpringAiVectorStoreRetriever(vectorStore, timeoutMs = 5000)

            val results = retriever.retrieve(listOf("문서 쿼리"), topK = 5)

            assertNotNull(results[0].source) { "source 필드가 null이 아니어야 한다" }
            assertEquals("docs/manual.md", results[0].source) {
                "source 메타데이터가 올바르게 매핑되어야 한다"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. DefaultRagPipeline — 누락된 경로 테스트
    // ──────────────────────────────────────────────────────────────────

    @Nested
    inner class DefaultRagPipelineGaps {

        @Test
        fun `쿼리 변환기가 있으면 변환된 쿼리로 검색을 수행한다`() = runTest {
            val queryTransformer = mockk<QueryTransformer>()
            val retriever = mockk<DocumentRetriever>()

            coEvery { queryTransformer.transform("원본 쿼리") } returns listOf(
                "변환된 쿼리 A", "변환된 쿼리 B"
            )
            coEvery { retriever.retrieve(listOf("변환된 쿼리 A", "변환된 쿼리 B"), any()) } returns listOf(
                RetrievedDocument(id = "doc1", content = "변환된 쿼리로 찾은 문서", score = 0.9)
            )

            val pipeline = DefaultRagPipeline(
                queryTransformer = queryTransformer,
                retriever = retriever
            )

            val result = pipeline.retrieve(RagQuery(query = "원본 쿼리", topK = 5))

            assertTrue(result.hasDocuments) { "변환된 쿼리로 문서가 검색되어야 한다" }
            assertEquals("doc1", result.documents[0].id) {
                "변환된 쿼리로 검색된 문서 ID가 일치해야 한다"
            }
            coVerify { queryTransformer.transform("원본 쿼리") }
        }

        @Test
        fun `컨텍스트 압축기가 빈 결과를 반환하면 RagContext_EMPTY를 반환한다`() = runTest {
            val retriever = mockk<DocumentRetriever>()
            val compressor = mockk<ContextCompressor>()

            val docs = listOf(
                RetrievedDocument(id = "d1", content = "압축 전 내용", score = 0.9)
            )
            coEvery { retriever.retrieve(any(), any()) } returns docs
            coEvery { compressor.compress("압축 테스트", docs) } returns emptyList()

            val pipeline = DefaultRagPipeline(
                retriever = retriever,
                contextCompressor = compressor
            )

            val result = pipeline.retrieve(RagQuery(query = "압축 테스트", topK = 5))

            assertEquals(RagContext.EMPTY, result) {
                "압축기가 빈 결과를 반환하면 RagContext.EMPTY여야 한다"
            }
        }

        @Test
        fun `rerank=false이면 리랭커를 호출하지 않고 topK로 자른다`() = runTest {
            val retriever = InMemoryDocumentRetriever()
            retriever.addDocuments(
                (1..5).map { i ->
                    RetrievedDocument(id = "doc-$i", content = "공통 키워드 내용 $i", score = 0.9 - i * 0.1)
                }
            )
            val reranker = mockk<DocumentReranker>()

            val pipeline = DefaultRagPipeline(
                retriever = retriever,
                reranker = reranker
            )

            val result = pipeline.retrieve(
                RagQuery(query = "공통 키워드", topK = 3, rerank = false)
            )

            // reranker가 호출되지 않아야 한다
            coVerify(exactly = 0) { reranker.rerank(any(), any(), any()) }
            assertTrue(result.documents.size <= 3) {
                "rerank=false이면 topK 제한이 적용되어야 한다, 실제: ${result.documents.size}"
            }
        }

        @Test
        fun `검색 결과가 비어있으면 RagContext_EMPTY를 반환한다`() = runTest {
            val retriever = InMemoryDocumentRetriever()

            val pipeline = DefaultRagPipeline(retriever = retriever)

            val result = pipeline.retrieve(RagQuery(query = "존재하지 않는 내용", topK = 5))

            assertEquals(RagContext.EMPTY, result) {
                "검색 결과가 없으면 RagContext.EMPTY여야 한다"
            }
            assertFalse(result.hasDocuments) { "hasDocuments가 false여야 한다" }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. RagIngestionDocumentSupport — 전체 미테스트 영역
    // ──────────────────────────────────────────────────────────────────

    @Nested
    inner class RagIngestionDocumentSupportTests {

        private fun makeCandidate(
            runId: String = "run-1",
            userId: String = "user-1",
            query: String = "Kotlin 코루틴이란?",
            response: String = "Kotlin 코루틴은 비동기 처리 방식입니다.",
            sessionId: String? = null,
            channel: String? = null
        ) = RagIngestionCandidate(
            id = "candidate-1",
            runId = runId,
            userId = userId,
            query = query,
            response = response,
            sessionId = sessionId,
            channel = channel,
            capturedAt = Instant.parse("2024-01-01T00:00:00Z")
        )

        @Test
        fun `toDocument는 Q-A 형식의 콘텐츠를 생성한다`() {
            val candidate = makeCandidate(query = "Kotlin이란?", response = "Kotlin은 JVM 언어다.")

            val doc = candidate.toDocument("doc-001")

            assertTrue(doc.text.orEmpty().contains("Q: Kotlin이란?")) {
                "문서 콘텐츠에 'Q: 쿼리' 형식이 포함되어야 한다, 실제: '${doc.text}'"
            }
            assertTrue(doc.text.orEmpty().contains("A: Kotlin은 JVM 언어다.")) {
                "문서 콘텐츠에 'A: 응답' 형식이 포함되어야 한다, 실제: '${doc.text}'"
            }
        }

        @Test
        fun `toDocument는 지정된 documentId를 사용한다`() {
            val candidate = makeCandidate()

            val doc = candidate.toDocument("fixed-doc-id")

            assertEquals("fixed-doc-id", doc.id) {
                "문서 ID가 지정된 값과 일치해야 한다, 실제: '${doc.id}'"
            }
        }

        @Test
        fun `toDocument의 documentId 기본값은 UUID 형식이다`() {
            val candidate = makeCandidate()

            val doc = candidate.toDocument()  // 기본값 사용

            val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
            assertTrue(uuidPattern.matches(doc.id)) {
                "기본 documentId는 UUID 형식이어야 한다, 실제: '${doc.id}'"
            }
        }

        @Test
        fun `toDocument는 source=rag_ingestion_candidate 메타데이터를 포함한다`() {
            val candidate = makeCandidate(runId = "run-42", userId = "user-99")

            val doc = candidate.toDocument("doc-meta")

            assertEquals("rag_ingestion_candidate", doc.metadata["source"].toString()) {
                "source 메타데이터가 'rag_ingestion_candidate'여야 한다"
            }
            assertEquals("run-42", doc.metadata["runId"].toString()) {
                "runId 메타데이터가 일치해야 한다"
            }
            assertEquals("user-99", doc.metadata["userId"].toString()) {
                "userId 메타데이터가 일치해야 한다"
            }
        }

        @Test
        fun `toDocument는 sessionId가 있으면 메타데이터에 포함한다`() {
            val candidate = makeCandidate(sessionId = "session-abc")

            val doc = candidate.toDocument("doc-session")

            assertEquals("session-abc", doc.metadata["sessionId"].toString()) {
                "sessionId가 있으면 메타데이터에 포함되어야 한다"
            }
        }

        @Test
        fun `toDocument는 sessionId가 null이면 메타데이터에 포함하지 않는다`() {
            val candidate = makeCandidate(sessionId = null)

            val doc = candidate.toDocument("doc-no-session")

            assertFalse(doc.metadata.containsKey("sessionId")) {
                "sessionId가 null이면 메타데이터에 포함되지 않아야 한다"
            }
        }

        @Test
        fun `toDocument는 channel이 있으면 메타데이터에 포함한다`() {
            val candidate = makeCandidate(channel = "slack")

            val doc = candidate.toDocument("doc-channel")

            assertEquals("slack", doc.metadata["channel"].toString()) {
                "channel이 있으면 메타데이터에 포함되어야 한다"
            }
        }

        @Test
        fun `toDocument는 channel이 빈 문자열이면 메타데이터에 포함하지 않는다`() {
            val candidate = makeCandidate(channel = "")

            val doc = candidate.toDocument("doc-empty-channel")

            assertFalse(doc.metadata.containsKey("channel")) {
                "channel이 빈 문자열이면 메타데이터에 포함되지 않아야 한다"
            }
        }

        @Test
        fun `toDocuments는 청커가 null이면 단일 문서를 반환한다`() {
            val candidate = makeCandidate()

            val docs = candidate.toDocuments("doc-single", chunker = null)

            assertEquals(1, docs.size) { "청커가 null이면 단일 문서를 반환해야 한다" }
        }

        @Test
        fun `toDocuments는 쿼리와 응답 앞뒤 공백을 제거하여 콘텐츠를 생성한다`() {
            val candidate = makeCandidate(
                query = "  공백 있는 쿼리  ",
                response = "  공백 있는 응답  "
            )

            val docs = candidate.toDocuments("doc-trim")

            assertTrue(docs[0].text.orEmpty().contains("Q: 공백 있는 쿼리")) {
                "쿼리의 앞뒤 공백이 제거되어야 한다, 실제: '${docs[0].text}'"
            }
            assertTrue(docs[0].text.orEmpty().contains("A: 공백 있는 응답")) {
                "응답의 앞뒤 공백이 제거되어야 한다, 실제: '${docs[0].text}'"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. InMemoryRagIngestionPolicyStore — 누락된 케이스
    // ──────────────────────────────────────────────────────────────────

    @Nested
    inner class InMemoryRagIngestionPolicyStoreGaps {

        private fun samplePolicy() = RagIngestionPolicy(
            enabled = true,
            requireReview = false,
            allowedChannels = setOf("slack"),
            minQueryChars = 10,
            minResponseChars = 20,
            blockedPatterns = emptySet()
        )

        @Test
        fun `초기값 없이 생성하면 getOrNull이 null을 반환한다`() {
            val store = InMemoryRagIngestionPolicyStore()

            assertNull(store.getOrNull()) { "초기값 없는 저장소는 null을 반환해야 한다" }
        }

        @Test
        fun `초기값으로 생성하면 getOrNull이 해당 정책을 반환한다`() {
            val policy = samplePolicy()
            val store = InMemoryRagIngestionPolicyStore(policy)

            val result = store.getOrNull()

            assertNotNull(result) { "초기값이 있으면 null이 아니어야 한다" }
            assertEquals(true, result!!.enabled) { "초기 정책의 enabled가 일치해야 한다" }
        }

        @Test
        fun `save는 정책을 저장하고 이후 getOrNull로 조회할 수 있다`() {
            val store = InMemoryRagIngestionPolicyStore()
            val policy = samplePolicy()

            store.save(policy)
            val result = store.getOrNull()

            assertNotNull(result) { "저장 후 getOrNull은 null이 아니어야 한다" }
            assertEquals(setOf("slack"), result!!.allowedChannels) {
                "저장된 정책의 allowedChannels가 일치해야 한다"
            }
        }

        @Test
        fun `save는 기존 정책의 createdAt을 보존한다`() {
            val fixedTime = Instant.parse("2024-01-01T00:00:00Z")
            val initialPolicy = samplePolicy().copy(createdAt = fixedTime)
            val store = InMemoryRagIngestionPolicyStore(initialPolicy)

            // 업데이트된 정책으로 재저장
            val updatedPolicy = samplePolicy().copy(enabled = false)
            val saved = store.save(updatedPolicy)

            assertEquals(fixedTime, saved.createdAt) {
                "재저장 시 기존 createdAt이 보존되어야 한다, 실제: ${saved.createdAt}"
            }
        }

        @Test
        fun `delete는 정책을 삭제하고 이후 getOrNull이 null을 반환한다`() {
            val store = InMemoryRagIngestionPolicyStore(samplePolicy())

            val deleted = store.delete()
            val afterDelete = store.getOrNull()

            assertTrue(deleted) { "삭제 성공 시 true를 반환해야 한다" }
            assertNull(afterDelete) { "삭제 후 getOrNull은 null이어야 한다" }
        }

        @Test
        fun `delete는 정책이 없을 때 false를 반환한다`() {
            val store = InMemoryRagIngestionPolicyStore()

            val result = store.delete()

            assertFalse(result) { "삭제할 정책이 없으면 false를 반환해야 한다" }
        }

        @Test
        fun `연속 save는 최신 정책으로 덮어쓴다`() {
            val store = InMemoryRagIngestionPolicyStore()

            store.save(samplePolicy().copy(enabled = true))
            store.save(samplePolicy().copy(enabled = false))

            val result = store.getOrNull()
            assertEquals(false, result!!.enabled) {
                "연속 저장 후 최신 정책이 반환되어야 한다"
            }
        }
    }
}
