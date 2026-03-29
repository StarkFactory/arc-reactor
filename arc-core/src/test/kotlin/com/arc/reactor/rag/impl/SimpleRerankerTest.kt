package com.arc.reactor.rag.impl

import com.arc.reactor.rag.model.RetrievedDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [SimpleScoreReranker], [KeywordWeightedReranker], [DiversityReranker] 단위 테스트.
 */
class SimpleRerankerTest {

    // ───────────────────────── 헬퍼 ─────────────────────────

    private fun doc(
        id: String,
        content: String,
        score: Double
    ) = RetrievedDocument(id = id, content = content, score = score)

    // ───────────────────────── SimpleScoreReranker ─────────────────────────

    @Nested
    inner class SimpleScoreRerankerTest {

        private val reranker = SimpleScoreReranker()

        @Test
        fun `점수 내림차순으로 정렬하여 상위 topK만 반환한다`() = runTest {
            val docs = listOf(
                doc("low", "내용 낮음", 0.1),
                doc("high", "내용 높음", 0.9),
                doc("mid", "내용 중간", 0.5)
            )

            val result = reranker.rerank("쿼리", docs, 2)

            assertEquals(2, result.size) { "topK=2이면 2개만 반환해야 한다" }
            assertEquals("high", result[0].id) { "첫 번째는 점수가 가장 높은 'high'여야 한다" }
            assertEquals("mid", result[1].id) { "두 번째는 점수가 중간인 'mid'여야 한다" }
        }

        @Test
        fun `빈 목록 입력이면 빈 목록을 반환한다`() = runTest {
            val result = reranker.rerank("쿼리", emptyList(), 5)

            assertTrue(result.isEmpty()) { "빈 입력에 대해 빈 결과를 반환해야 한다" }
        }

        @Test
        fun `topK가 문서 수보다 크면 모든 문서를 반환한다`() = runTest {
            val docs = listOf(
                doc("a", "내용 A", 0.7),
                doc("b", "내용 B", 0.3)
            )

            val result = reranker.rerank("쿼리", docs, 10)

            assertEquals(2, result.size) { "topK > 문서 수이면 모든 문서를 반환해야 한다" }
        }

        @Test
        fun `topK가 1이면 점수가 가장 높은 단일 문서를 반환한다`() = runTest {
            val docs = listOf(
                doc("best", "최고 내용", 0.99),
                doc("worst", "최저 내용", 0.01)
            )

            val result = reranker.rerank("쿼리", docs, 1)

            assertEquals(1, result.size) { "topK=1이면 1개만 반환해야 한다" }
            assertEquals("best", result[0].id) { "topK=1이면 최고 점수 문서만 반환해야 한다" }
        }

        @Test
        fun `단일 문서는 그대로 반환된다`() = runTest {
            val docs = listOf(doc("only", "유일한 문서", 0.5))

            val result = reranker.rerank("쿼리", docs, 5)

            assertEquals(1, result.size) { "단일 문서는 1개를 반환해야 한다" }
            assertEquals("only", result[0].id) { "단일 문서의 ID가 일치해야 한다" }
        }
    }

    // ───────────────────────── KeywordWeightedReranker ─────────────────────────

    @Nested
    inner class KeywordWeightedRerankerTest {

        @Test
        fun `키워드가 많이 매칭되는 문서가 상위에 배치된다`() = runTest {
            val reranker = KeywordWeightedReranker(keywordWeight = 0.8)
            val docs = listOf(
                doc("low-score-keyword-match", "kotlin 언어 소개 문서", 0.3),
                doc("high-score-no-keyword", "무관한 주제의 날씨 기사", 0.9)
            )

            val result = reranker.rerank("kotlin 언어", docs, 2)

            assertEquals("low-score-keyword-match", result[0].id) {
                "키워드 가중치가 높을 때 키워드 매칭 문서가 상위여야 한다"
            }
        }

        @Test
        fun `keywordWeight=0이면 원본 점수 순서를 유지한다`() = runTest {
            val reranker = KeywordWeightedReranker(keywordWeight = 0.0)
            val docs = listOf(
                doc("low", "kotlin 완전 일치", 0.2),
                doc("high", "kotlin 완전 일치", 0.8)
            )

            val result = reranker.rerank("kotlin", docs, 2)

            assertEquals("high", result[0].id) {
                "keywordWeight=0이면 원본 점수가 높은 문서가 상위여야 한다"
            }
        }

        @Test
        fun `빈 쿼리는 키워드 점수 0으로 원본 점수 순서를 유지한다`() = runTest {
            val reranker = KeywordWeightedReranker()
            val docs = listOf(
                doc("b", "문서 B", 0.9),
                doc("a", "문서 A", 0.1)
            )

            val result = reranker.rerank("", docs, 2)

            assertEquals("b", result[0].id) { "빈 쿼리이면 원본 점수 순서를 유지해야 한다" }
        }

        @Test
        fun `빈 문서 목록은 빈 결과를 반환한다`() = runTest {
            val reranker = KeywordWeightedReranker()

            val result = reranker.rerank("쿼리", emptyList(), 5)

            assertTrue(result.isEmpty()) { "빈 입력에 대해 빈 결과를 반환해야 한다" }
        }

        @Test
        fun `topK를 초과하지 않는다`() = runTest {
            val reranker = KeywordWeightedReranker()
            val docs = (1..10).map { doc("doc-$it", "문서 내용 $it", it.toDouble() / 10) }

            val result = reranker.rerank("문서", docs, 3)

            assertEquals(3, result.size) { "topK=3이면 정확히 3개만 반환해야 한다" }
        }

        @Test
        fun `keywordWeight=1_0이면 키워드 점수만으로 정렬된다`() = runTest {
            val reranker = KeywordWeightedReranker(keywordWeight = 1.0)
            val docs = listOf(
                doc("no-keyword", "완전히 무관한 내용", 1.0),
                doc("full-keyword", "spring boot 가이드 spring boot 설명", 0.0)
            )

            // no-keyword: 1.0 * 0 + 0.0 * 1.0 = 0.0 (쿼리 단어 없음)
            // full-keyword: 0.0 * 0 + 1.0 * 1.0 = 1.0 (쿼리 단어 전부 매칭)
            val result = reranker.rerank("spring boot", docs, 2)

            assertEquals("full-keyword", result[0].id) {
                "keywordWeight=1이면 키워드 매칭 문서가 상위여야 한다"
            }
        }

        @Test
        fun `쿼리 단어 일부만 매칭되면 키워드 점수는 매칭 비율에 비례한다`() = runTest {
            val reranker = KeywordWeightedReranker(keywordWeight = 1.0)
            val docs = listOf(
                doc("half", "kotlin 프레임워크", 0.0),   // 쿼리 2단어 중 1단어 매칭
                doc("full", "kotlin spring 예제", 0.0)   // 쿼리 2단어 모두 매칭
            )

            val result = reranker.rerank("kotlin spring", docs, 2)

            assertEquals("full", result[0].id) {
                "더 많은 키워드를 포함하는 문서가 상위여야 한다"
            }
            result[0].score shouldBeGreaterThan result[1].score
        }
    }

    // ───────────────────────── DiversityReranker ─────────────────────────

    @Nested
    inner class DiversityRerankerTest {

        @Test
        fun `빈 목록 입력이면 빈 목록을 반환한다`() = runTest {
            val reranker = DiversityReranker()

            val result = reranker.rerank("쿼리", emptyList(), 5)

            assertTrue(result.isEmpty()) { "빈 입력에 대해 빈 결과를 반환해야 한다" }
        }

        @Test
        fun `단일 문서는 그대로 반환된다`() = runTest {
            val reranker = DiversityReranker()
            val docs = listOf(doc("only", "유일한 문서", 0.8))

            val result = reranker.rerank("쿼리", docs, 5)

            assertEquals(1, result.size) { "단일 문서는 1개를 반환해야 한다" }
            assertEquals("only", result[0].id) { "단일 문서의 ID가 일치해야 한다" }
        }

        @Test
        fun `첫 번째로 선택되는 문서는 항상 점수가 가장 높은 문서다`() = runTest {
            val reranker = DiversityReranker()
            val docs = listOf(
                doc("second", "내용 A", 0.6),
                doc("best", "내용 B", 0.95),
                doc("third", "내용 C", 0.4)
            )

            val result = reranker.rerank("쿼리", docs, 3)

            assertEquals("best", result[0].id) { "첫 번째는 항상 최고 점수 문서여야 한다" }
        }

        @Test
        fun `다양성 선호 시 유사한 문서보다 상이한 문서가 우선 선택된다`() = runTest {
            val reranker = DiversityReranker(lambda = 0.0)
            // doc1 선택 후 — doc2는 doc1과 유사, doc3은 doc1과 상이
            val docs = listOf(
                doc("doc1", "kotlin 프로그래밍 언어 기능 소개", 0.95),
                doc("doc2", "kotlin 프로그래밍 언어 문법 설명", 0.90),
                doc("doc3", "spring boot 웹 프레임워크 배포 가이드", 0.80)
            )

            val result = reranker.rerank("프로그래밍", docs, 3)

            assertEquals("doc1", result[0].id) { "첫 번째는 최고 점수 문서여야 한다" }
            assertEquals("doc3", result[1].id) { "lambda=0이면 두 번째는 doc1과 가장 다른 doc3이어야 한다" }
        }

        @Test
        fun `관련도만 선호할 때(lambda=1) 점수 순서를 유지한다`() = runTest {
            val reranker = DiversityReranker(lambda = 1.0)
            val docs = listOf(
                doc("doc3", "spring boot 웹 프레임워크", 0.80),
                doc("doc1", "kotlin 언어 기능 소개", 0.95),
                doc("doc2", "kotlin 언어 문법 설명", 0.90)
            )

            val result = reranker.rerank("kotlin", docs, 3)

            assertEquals("doc1", result[0].id) { "lambda=1이면 점수 1위가 첫 번째여야 한다" }
            assertEquals("doc2", result[1].id) { "lambda=1이면 점수 2위가 두 번째여야 한다" }
        }

        @Test
        fun `topK를 초과하지 않는다`() = runTest {
            val reranker = DiversityReranker()
            val docs = (1..10).map { doc("doc-$it", "고유 내용 주제 $it 번", it.toDouble() / 10) }

            val result = reranker.rerank("주제", docs, 4)

            assertEquals(4, result.size) { "topK=4이면 정확히 4개만 반환해야 한다" }
        }

        @Test
        fun `동일 내용의 두 문서가 있을 때 lambda=0이면 두 번째는 가장 다른 문서가 선택된다`() = runTest {
            val reranker = DiversityReranker(lambda = 0.0)
            val docs = listOf(
                doc("a", "같은 내용 완전히 동일한 단어들", 0.9),
                doc("b", "같은 내용 완전히 동일한 단어들", 0.8),
                doc("c", "전혀 다른 주제 물리학 실험 방사선", 0.7)
            )

            val result = reranker.rerank("내용", docs, 3)

            assertEquals("a", result[0].id) { "첫 번째는 최고 점수 문서여야 한다" }
            assertEquals("c", result[1].id) {
                "a와 동일한 b보다 상이한 c가 다양성 선호 시 우선이어야 한다"
            }
        }

        @Test
        fun `반환된 문서 목록에 요청한 도큐먼트들이 모두 포함된다`() = runTest {
            val reranker = DiversityReranker(lambda = 0.5)
            val docs = listOf(
                doc("a", "고유 내용 A", 0.8),
                doc("b", "전혀 다른 내용 B", 0.6)
            )

            val result = reranker.rerank("쿼리", docs, 2)

            assertTrue(result.any { it.id == "a" }) { "결과에 'a' 문서가 포함되어야 한다" }
            assertTrue(result.any { it.id == "b" }) { "결과에 'b' 문서가 포함되어야 한다" }
        }
    }

    // ───────────────────────── DiversityReranker Jaccard 유사도 ─────────────────────────

    @Nested
    inner class JaccardSimilarityTest {

        @Test
        fun `완전히 동일한 내용의 두 문서는 높은 유사도로 다양성 선호 시 같이 선택되지 않는다`() = runTest {
            // lambda=0.5로 관련도와 다양성을 균형 있게 고려
            val reranker = DiversityReranker(lambda = 0.5)
            val docs = listOf(
                doc("identical-1", "kotlin 프로그래밍 언어 기능 소개 코드", 0.9),
                doc("identical-2", "kotlin 프로그래밍 언어 기능 소개 코드", 0.85),
                doc("diverse", "데이터베이스 쿼리 최적화 인덱싱 기법", 0.7)
            )

            val result = reranker.rerank("kotlin", docs, 2)

            assertEquals("identical-1", result[0].id) { "첫 번째는 최고 점수 문서여야 한다" }
            // identical-2와 diverse 중 diverse가 더 다양함 → diverse 선택
            assertEquals("diverse", result[1].id) {
                "동일 내용 문서보다 다양한 내용의 문서가 두 번째로 선택되어야 한다"
            }
        }

        @Test
        fun `단어가 전혀 겹치지 않는 두 문서의 Jaccard 유사도는 0으로 처리된다`() = runTest {
            // Jaccard(A, B) = 0이면 다양성 패널티가 없음 → 관련도만으로 정렬
            val reranker = DiversityReranker(lambda = 0.5)
            val docs = listOf(
                doc("a", "alpha beta gamma delta", 0.9),
                doc("b", "omega epsilon zeta eta", 0.8),
                doc("c", "alpha beta gamma delta", 0.7)  // a와 동일
            )

            val result = reranker.rerank("쿼리", docs, 3)
            val ids = result.map { it.id }

            assertEquals("a", ids[0]) { "첫 번째는 최고 점수 문서여야 한다" }
            // b는 a와 겹치는 단어가 없어 다양성 패널티 0 → 관련도 기준 두 번째
            assertEquals("b", ids[1]) { "a와 단어가 전혀 겹치지 않는 b가 두 번째여야 한다" }
        }
    }
}
