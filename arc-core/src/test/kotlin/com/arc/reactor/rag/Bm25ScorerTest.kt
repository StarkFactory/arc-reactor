package com.arc.reactor.rag

import com.arc.reactor.rag.search.Bm25Scorer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class Bm25ScorerTest {

    private lateinit var scorer: Bm25Scorer

    @BeforeEach
    fun setup() {
        scorer = Bm25Scorer()
    }

    @Nested
    inner class Indexing {

        @Test
        fun `size should increase after indexing`() {
            scorer.index("doc-1", "kotlin spring boot")
            assertEquals(1, scorer.size) { "Scorer should contain exactly 1 document after indexing one doc" }
        }

        @Test
        fun `re-indexing same docId should not grow size`() {
            scorer.index("doc-1", "kotlin spring boot")
            scorer.index("doc-1", "kotlin updated content")
            assertEquals(1, scorer.size) { "Re-indexing the same docId should not duplicate the entry" }
        }

        @Test
        fun `clear should remove all documents`() {
            scorer.index("doc-1", "some content")
            scorer.index("doc-2", "more content")
            scorer.clear()
            assertEquals(0, scorer.size) { "clear() should remove all indexed documents" }
        }
    }

    @Nested
    inner class Scoring {

        @Test
        fun `score should return 0 for unknown document`() {
            val result = scorer.score("kotlin", "nonexistent-doc")
            assertEquals(0.0, result, 0.0001) { "Score for non-indexed docId should be 0.0" }
        }

        @Test
        fun `score should be positive when query term matches document`() {
            scorer.index("doc-1", "kotlin is a modern programming language")
            val result = scorer.score("kotlin", "doc-1")
            assertTrue(result > 0.0) { "BM25 score should be positive when query term matches document" }
        }

        @Test
        fun `document containing query term should score higher than one without`() {
            scorer.index("doc-match", "ProjectApollo is the internal deployment system")
            scorer.index("doc-nomatch", "unrelated content about unrelated things")
            val matchScore = scorer.score("ProjectApollo", "doc-match")
            val noMatchScore = scorer.score("ProjectApollo", "doc-nomatch")
            assertTrue(matchScore > noMatchScore) {
                "Doc containing 'ProjectApollo' should score higher: match=$matchScore, nomatch=$noMatchScore"
            }
        }

        @Test
        fun `document with higher term frequency should score higher`() {
            scorer.index("doc-high", "TeamAlpha TeamAlpha TeamAlpha leads the backend guild")
            scorer.index("doc-low", "TeamAlpha is one of the teams")
            val highScore = scorer.score("TeamAlpha", "doc-high")
            val lowScore = scorer.score("TeamAlpha", "doc-low")
            assertTrue(highScore > lowScore) {
                "Higher term frequency should yield higher BM25 score: high=$highScore, low=$lowScore"
            }
        }
    }

    @Nested
    inner class Search {

        @Test
        fun `search should return empty list when index is empty`() {
            val results = scorer.search("anything", 10)
            assertTrue(results.isEmpty()) { "Search on empty index should return empty list" }
        }

        @Test
        fun `search results should be ordered by score descending`() {
            scorer.index("doc-1", "PlatformX PlatformX PlatformX service architecture")
            scorer.index("doc-2", "PlatformX handles requests")
            scorer.index("doc-3", "unrelated document about weather")

            val results = scorer.search("PlatformX", 10)

            assertTrue(results.size >= 2) { "Should return at least 2 matching documents for 'PlatformX'" }
            for (i in 0 until results.size - 1) {
                assertTrue(results[i].second >= results[i + 1].second) {
                    "Results should be sorted descending by score: " +
                        "rank $i score=${results[i].second}, rank ${i + 1} score=${results[i + 1].second}"
                }
            }
        }

        @Test
        fun `search should respect topK limit`() {
            for (i in 1..20) {
                scorer.index("doc-$i", "common keyword content repeated")
            }
            val results = scorer.search("common keyword", topK = 5)
            assertTrue(results.size <= 5) { "search(topK=5) should return at most 5 results, got ${results.size}" }
        }

        @Test
        fun `proper noun team name should rank at top`() {
            scorer.index("doc-guardian", "Team Guardian owns the authentication service")
            scorer.index("doc-platform", "the platform team handles infrastructure deployments")
            scorer.index("doc-infra", "infrastructure includes databases and network config")

            val results = scorer.search("Guardian authentication", 10)

            assertTrue(results.isNotEmpty()) { "Should find documents matching 'Guardian authentication'" }
            assertEquals("doc-guardian", results[0].first) {
                "doc-guardian should rank first for proper noun 'Guardian' query"
            }
        }

        @Test
        fun `proper noun person name should be found precisely`() {
            scorer.index("doc-john", "John Kim is the lead engineer on the Hermes project")
            scorer.index("doc-team", "the hermes project handles event streaming at scale")
            scorer.index("doc-other", "the platform engineering team works on tooling")

            val results = scorer.search("John Kim", 10)

            assertTrue(results.isNotEmpty()) { "Should find at least one document for 'John Kim'" }
            assertEquals("doc-john", results[0].first) {
                "Document mentioning 'John Kim' directly should rank first"
            }
        }

        @Test
        fun `Korean proper noun should be found`() {
            scorer.index("doc-kr-1", "플랫폼팀은 인프라를 담당합니다")
            scorer.index("doc-kr-2", "백엔드 서비스는 Spring Boot를 사용합니다")

            val results = scorer.search("플랫폼팀", 10)

            assertTrue(results.isNotEmpty()) { "Korean proper noun '플랫폼팀' should be found in index" }
            assertEquals("doc-kr-1", results[0].first) {
                "Document mentioning '플랫폼팀' should rank first"
            }
        }
    }

    @Nested
    inner class Normalization {

        @Test
        fun `search should be case-insensitive`() {
            scorer.index("doc-1", "Alpha Bravo Charlie project")

            val upper = scorer.score("ALPHA", "doc-1")
            val lower = scorer.score("alpha", "doc-1")
            val mixed = scorer.score("Alpha", "doc-1")

            assertTrue(upper > 0.0) { "Uppercase query should match lowercase-normalized index" }
            assertEquals(upper, lower, 0.0001) { "Scores should be equal regardless of query case" }
            assertEquals(upper, mixed, 0.0001) { "Mixed-case query should produce the same score" }
        }
    }
}
