package com.arc.reactor.rag

import com.arc.reactor.rag.impl.InMemoryDocumentRetriever
import com.arc.reactor.rag.model.RetrievedDocument
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MetadataFilteringTest {

    private lateinit var retriever: InMemoryDocumentRetriever

    @BeforeEach
    fun setup() {
        retriever = InMemoryDocumentRetriever()
        retriever.addDocuments(
            listOf(
                RetrievedDocument(
                    id = "doc-1", content = "kotlin programming guide",
                    metadata = mapOf("source" to "docs", "category" to "language"),
                    source = "docs"
                ),
                RetrievedDocument(
                    id = "doc-2", content = "kotlin spring boot tutorial",
                    metadata = mapOf("source" to "blog", "category" to "framework"),
                    source = "blog"
                ),
                RetrievedDocument(
                    id = "doc-3", content = "kotlin coroutines guide",
                    metadata = mapOf("source" to "docs", "category" to "language"),
                    source = "docs"
                ),
                RetrievedDocument(
                    id = "doc-4", content = "java programming basics",
                    metadata = mapOf("source" to "docs", "category" to "language"),
                    source = "docs"
                )
            )
        )
    }

    @Nested
    inner class SingleFilter {

        @Test
        fun `should filter by single metadata field`() = runBlocking {
            val results = retriever.retrieve(
                queries = listOf("kotlin"),
                topK = 10,
                filters = mapOf("source" to "docs")
            )

            assertTrue(results.all { it.metadata["source"] == "docs" }) {
                "All results should have source=docs, got: ${results.map { it.metadata["source"] }}"
            }
            assertEquals(2, results.size) { "Should return 2 docs matching kotlin + source=docs" }
        }

        @Test
        fun `should filter by category`() = runBlocking {
            val results = retriever.retrieve(
                queries = listOf("kotlin"),
                topK = 10,
                filters = mapOf("category" to "framework")
            )

            assertEquals(1, results.size) { "Should return only framework docs matching kotlin" }
            assertEquals("doc-2", results[0].id) { "Should return the spring boot tutorial" }
        }
    }

    @Nested
    inner class MultipleFilters {

        @Test
        fun `should apply multiple filters with AND logic`() = runBlocking {
            val results = retriever.retrieve(
                queries = listOf("kotlin"),
                topK = 10,
                filters = mapOf("source" to "docs", "category" to "language")
            )

            assertEquals(2, results.size) { "Should return docs matching kotlin + source=docs + category=language" }
            assertTrue(results.all {
                it.metadata["source"] == "docs" && it.metadata["category"] == "language"
            }) { "All results should match both filters" }
        }

        @Test
        fun `should return empty when filters exclude all matching docs`() = runBlocking {
            val results = retriever.retrieve(
                queries = listOf("kotlin"),
                topK = 10,
                filters = mapOf("source" to "blog", "category" to "language")
            )

            assertTrue(results.isEmpty()) {
                "No docs match kotlin + source=blog + category=language, got: ${results.size}"
            }
        }
    }

    @Nested
    inner class EmptyFilters {

        @Test
        fun `should return all matching docs when filters are empty`() = runBlocking {
            val results = retriever.retrieve(
                queries = listOf("kotlin"),
                topK = 10,
                filters = emptyMap()
            )

            assertEquals(3, results.size) { "Should return all 3 kotlin docs without filters" }
        }

        @Test
        fun `should return all matching docs when no filters parameter`() = runBlocking {
            val results = retriever.retrieve(
                queries = listOf("kotlin"),
                topK = 10
            )

            assertEquals(3, results.size) { "Should return all 3 kotlin docs with default filters" }
        }
    }

    @Nested
    inner class FilterWithNonexistentKey {

        @Test
        fun `should return empty when filtering by nonexistent metadata key`() = runBlocking {
            val results = retriever.retrieve(
                queries = listOf("kotlin"),
                topK = 10,
                filters = mapOf("author" to "john")
            )

            assertTrue(results.isEmpty()) {
                "No docs have 'author' metadata, should return empty, got: ${results.size}"
            }
        }
    }
}
