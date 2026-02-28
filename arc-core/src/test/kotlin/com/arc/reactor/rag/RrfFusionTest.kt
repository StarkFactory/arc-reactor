package com.arc.reactor.rag

import com.arc.reactor.rag.search.RrfFusion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RrfFusionTest {

    @Nested
    inner class EmptyInputs {

        @Test
        fun `fuse with both empty lists should return empty result`() {
            val result = RrfFusion.fuse(emptyList(), emptyList())
            assertTrue(result.isEmpty()) { "Fusing two empty lists should produce an empty result" }
        }

        @Test
        fun `fuse with empty vector list should use only BM25 ranks`() {
            val bm25 = listOf("doc-a" to 5.0, "doc-b" to 3.0)
            val result = RrfFusion.fuse(vectorResults = emptyList(), bm25Results = bm25)
            assertEquals(2, result.size) { "Should have 2 results when only BM25 provides ranks" }
            assertEquals("doc-a", result[0].first) { "doc-a should rank first (rank 0 in BM25)" }
        }

        @Test
        fun `fuse with empty BM25 list should use only vector ranks`() {
            val vector = listOf("doc-x" to 0.9, "doc-y" to 0.7)
            val result = RrfFusion.fuse(vectorResults = vector, bm25Results = emptyList())
            assertEquals(2, result.size) { "Should have 2 results when only vector provides ranks" }
            assertEquals("doc-x", result[0].first) { "doc-x should rank first (rank 0 in vector)" }
        }
    }

    @Nested
    inner class FusionOrdering {

        @Test
        fun `document appearing in both lists should have higher fused score than document in one list only`() {
            val vector = listOf("common" to 0.9, "vector-only" to 0.8)
            val bm25 = listOf("common" to 10.0, "bm25-only" to 8.0)

            val result = RrfFusion.fuse(vector, bm25)

            val commonScore = result.find { it.first == "common" }?.second
            val vectorOnlyScore = result.find { it.first == "vector-only" }?.second
            val bm25OnlyScore = result.find { it.first == "bm25-only" }?.second

            assertNotNull(commonScore) { "'common' doc should be present in fused result" }
            assertNotNull(vectorOnlyScore) { "'vector-only' doc should be present in fused result" }
            assertNotNull(bm25OnlyScore) { "'bm25-only' doc should be present in fused result" }

            assertTrue(commonScore!! > vectorOnlyScore!!) {
                "Doc in both lists should outscore doc in vector list only: common=$commonScore, vectorOnly=$vectorOnlyScore"
            }
            assertTrue(commonScore > bm25OnlyScore!!) {
                "Doc in both lists should outscore doc in BM25 list only: common=$commonScore, bm25Only=$bm25OnlyScore"
            }
        }

        @Test
        fun `results should be ordered by fused score descending`() {
            val vector = listOf("doc-1" to 0.9, "doc-2" to 0.8, "doc-3" to 0.7)
            val bm25 = listOf("doc-2" to 9.0, "doc-3" to 7.0, "doc-1" to 5.0)

            val result = RrfFusion.fuse(vector, bm25)

            assertTrue(result.isNotEmpty()) { "Fused result should not be empty" }
            for (i in 0 until result.size - 1) {
                assertTrue(result[i].second >= result[i + 1].second) {
                    "Results should be sorted descending: rank $i score=${result[i].second}, " +
                        "rank ${i + 1} score=${result[i + 1].second}"
                }
            }
        }

        @Test
        fun `all unique documents from both lists should appear in fused result`() {
            val vector = listOf("doc-v1" to 0.9, "doc-v2" to 0.8)
            val bm25 = listOf("doc-b1" to 5.0, "doc-v1" to 4.0)

            val result = RrfFusion.fuse(vector, bm25)
            val resultIds = result.map { it.first }.toSet()

            assertTrue("doc-v1" in resultIds) { "doc-v1 (in both lists) should be in fused result" }
            assertTrue("doc-v2" in resultIds) { "doc-v2 (vector only) should be in fused result" }
            assertTrue("doc-b1" in resultIds) { "doc-b1 (BM25 only) should be in fused result" }
        }
    }

    @Nested
    inner class Weights {

        @Test
        fun `higher vector weight should promote vector-only documents relative to bm25-only`() {
            val vector = listOf("v-only" to 0.9)
            val bm25 = listOf("b-only" to 9.0)

            val vectorBiased = RrfFusion.fuse(vector, bm25, vectorWeight = 0.9, bm25Weight = 0.1)
            val bm25Biased = RrfFusion.fuse(vector, bm25, vectorWeight = 0.1, bm25Weight = 0.9)

            val vScoreInVectorBiased = vectorBiased.find { it.first == "v-only" }!!.second
            val bScoreInVectorBiased = vectorBiased.find { it.first == "b-only" }!!.second
            val vScoreInBm25Biased = bm25Biased.find { it.first == "v-only" }!!.second
            val bScoreInBm25Biased = bm25Biased.find { it.first == "b-only" }!!.second

            assertTrue(vScoreInVectorBiased > bScoreInVectorBiased) {
                "With vector weight=0.9, vector-only doc should rank above bm25-only doc"
            }
            assertTrue(bScoreInBm25Biased > vScoreInBm25Biased) {
                "With bm25 weight=0.9, bm25-only doc should rank above vector-only doc"
            }
        }

        @Test
        fun `equal weights should produce symmetric scores for equal-ranked documents`() {
            val vector = listOf("doc-1" to 0.9)
            val bm25 = listOf("doc-2" to 9.0)

            val result = RrfFusion.fuse(vector, bm25, vectorWeight = 0.5, bm25Weight = 0.5)

            val score1 = result.find { it.first == "doc-1" }!!.second
            val score2 = result.find { it.first == "doc-2" }!!.second

            assertEquals(score1, score2, 0.00001) {
                "Docs at the same rank in equal-weight fusion should have equal scores: " +
                    "doc-1=$score1, doc-2=$score2"
            }
        }
    }

    @Nested
    inner class RrfFormula {

        @Test
        fun `rrf score should use K constant of 60`() {
            assertEquals(60.0, RrfFusion.K, 0.0001) {
                "RrfFusion.K should be the standard 60.0 smoothing constant"
            }
        }

        @Test
        fun `rank 0 document with weight 0_5 should have score near 1 over 122`() {
            val vector = listOf("doc-1" to 1.0)
            val result = RrfFusion.fuse(vector, emptyList(), vectorWeight = 0.5)
            val score = result[0].second
            // expected: 0.5 / (60 + 0 + 1) = 0.5 / 61 ≈ 0.008196
            val expected = 0.5 / (RrfFusion.K + 1)
            assertEquals(expected, score, 0.000001) {
                "RRF score for rank-0 doc with weight 0.5 should be 0.5/(K+1)=$expected, got $score"
            }
        }
    }
}
