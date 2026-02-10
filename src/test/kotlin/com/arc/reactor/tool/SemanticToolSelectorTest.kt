package com.arc.reactor.tool

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel

class SemanticToolSelectorTest {

    private lateinit var embeddingModel: EmbeddingModel
    private val tools = listOf(
        createTool("search_orders", "Search and retrieve customer orders by ID or date range"),
        createTool("process_refund", "Process refund for a customer order"),
        createTool("track_shipping", "Track shipping status and delivery estimates"),
        createTool("send_email", "Send email notifications to customers"),
        createTool("calculate_tax", "Calculate tax for order total"),
        createTool("generate_report", "Generate sales analytics report")
    )

    @BeforeEach
    fun setup() {
        embeddingModel = mockk()
    }

    @Nested
    inner class SelectionLogic {

        @Test
        fun `should return all tools when count below maxResults`() {
            val smallToolList = tools.take(3)
            val selector = SemanticToolSelector(embeddingModel, maxResults = 10)

            val result = selector.select("any prompt", smallToolList)

            assertEquals(3, result.size) { "Should return all tools when count <= maxResults" }
        }

        @Test
        fun `should return empty for empty tool list`() {
            val selector = SemanticToolSelector(embeddingModel)

            val result = selector.select("any prompt", emptyList())

            assertTrue(result.isEmpty()) { "Should return empty for empty tool list" }
        }

        @Test
        fun `should select tools by semantic similarity`() {
            // Simulate embeddings where "refund" prompt is close to "process_refund" tool
            val refundToolEmbedding = floatArrayOf(0.9f, 0.1f, 0.0f)
            val searchToolEmbedding = floatArrayOf(0.1f, 0.9f, 0.0f)
            val shippingToolEmbedding = floatArrayOf(0.0f, 0.1f, 0.9f)
            val emailToolEmbedding = floatArrayOf(0.2f, 0.3f, 0.5f)
            val taxToolEmbedding = floatArrayOf(0.3f, 0.3f, 0.3f)
            val reportToolEmbedding = floatArrayOf(0.1f, 0.5f, 0.4f)
            val promptEmbedding = floatArrayOf(0.85f, 0.15f, 0.0f) // Close to refund

            // Mock batch embedding (for tool descriptions)
            every { embeddingModel.embed(any<List<String>>()) } returns listOf(
                searchToolEmbedding, refundToolEmbedding, shippingToolEmbedding,
                emailToolEmbedding, taxToolEmbedding, reportToolEmbedding
            )
            // Mock single embedding (for prompt)
            every { embeddingModel.embed(any<String>()) } returns promptEmbedding

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.5,
                maxResults = 3
            )

            val result = selector.select("I want to refund my order", tools)

            // process_refund should be the top match (highest cosine similarity with prompt)
            assertTrue(result.isNotEmpty()) { "Should return at least one tool" }
            assertEquals("process_refund", result[0].name) {
                "First tool should be process_refund (closest to 'refund' prompt), got: ${result.map { it.name }}"
            }
            assertTrue(result.size <= 3) { "Should not exceed maxResults=3, got: ${result.size}" }
        }

        @Test
        fun `should fall back to all tools when no tool meets threshold`() {
            // All tools have very low similarity
            val lowSimilarity = floatArrayOf(0.1f, 0.0f, 0.0f)
            val promptEmbedding = floatArrayOf(0.0f, 0.0f, 1.0f) // Orthogonal to all tools

            every { embeddingModel.embed(any<List<String>>()) } returns
                tools.map { lowSimilarity }
            every { embeddingModel.embed(any<String>()) } returns promptEmbedding

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.9, // Very high threshold
                maxResults = 3
            )

            val result = selector.select("completely unrelated topic", tools)

            assertEquals(tools.size, result.size) {
                "Should return all tools when none meets threshold"
            }
        }

        @Test
        fun `should fall back to all tools when embedding fails`() {
            every { embeddingModel.embed(any<List<String>>()) } throws RuntimeException("API error")

            val selector = SemanticToolSelector(embeddingModel, maxResults = 3)

            val result = selector.select("any prompt", tools)

            assertEquals(tools.size, result.size) {
                "Should return all tools on embedding failure"
            }
        }
    }

    @Nested
    inner class EmbeddingCache {

        @Test
        fun `should cache tool embeddings and reuse on same tool list`() {
            val toolEmbeddings = tools.map { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<List<String>>()) } returns toolEmbeddings
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.0,
                maxResults = 3
            )

            // First call: should embed tools
            selector.select("prompt 1", tools)
            // Second call with same tools: should NOT re-embed tools
            selector.select("prompt 2", tools)

            // Batch embed should be called only once (for tools), embed(String) called twice (for prompts)
            verify(exactly = 1) { embeddingModel.embed(any<List<String>>()) }
            verify(exactly = 2) { embeddingModel.embed(any<String>()) }
        }

        @Test
        fun `should refresh cache when tool list changes`() {
            val toolsA = tools  // 6 tools
            val toolsB = tools.drop(1)  // 5 tools (different list)
            val embeddingsA = toolsA.map { floatArrayOf(0.5f, 0.5f) }
            val embeddingsB = toolsB.map { floatArrayOf(0.5f, 0.5f) }

            every { embeddingModel.embed(any<List<String>>()) } returnsMany listOf(embeddingsA, embeddingsB)
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.0,
                maxResults = 3  // Below both list sizes to trigger semantic selection
            )

            // First call with full tool list
            selector.select("prompt", toolsA)
            // Second call with different tool list
            selector.select("prompt", toolsB)

            // Batch embed called twice (different tool lists)
            verify(exactly = 2) { embeddingModel.embed(any<List<String>>()) }
        }
    }

    @Nested
    inner class CosineSimilarityTest {

        @Test
        fun `identical vectors should have similarity 1`() {
            val a = floatArrayOf(1.0f, 0.0f, 0.0f)
            val b = floatArrayOf(1.0f, 0.0f, 0.0f)

            val similarity = SemanticToolSelector.cosineSimilarity(a, b)

            assertEquals(1.0, similarity, 0.001) { "Identical vectors should have similarity 1.0" }
        }

        @Test
        fun `orthogonal vectors should have similarity 0`() {
            val a = floatArrayOf(1.0f, 0.0f, 0.0f)
            val b = floatArrayOf(0.0f, 1.0f, 0.0f)

            val similarity = SemanticToolSelector.cosineSimilarity(a, b)

            assertEquals(0.0, similarity, 0.001) { "Orthogonal vectors should have similarity 0.0" }
        }

        @Test
        fun `opposite vectors should have similarity -1`() {
            val a = floatArrayOf(1.0f, 0.0f, 0.0f)
            val b = floatArrayOf(-1.0f, 0.0f, 0.0f)

            val similarity = SemanticToolSelector.cosineSimilarity(a, b)

            assertEquals(-1.0, similarity, 0.001) { "Opposite vectors should have similarity -1.0" }
        }

        @Test
        fun `zero vector should have similarity 0`() {
            val a = floatArrayOf(0.0f, 0.0f, 0.0f)
            val b = floatArrayOf(1.0f, 0.0f, 0.0f)

            val similarity = SemanticToolSelector.cosineSimilarity(a, b)

            assertEquals(0.0, similarity, 0.001) { "Zero vector should have similarity 0.0" }
        }

        @Test
        fun `should reject mismatched dimensions`() {
            val a = floatArrayOf(1.0f, 0.0f)
            val b = floatArrayOf(1.0f, 0.0f, 0.0f)

            assertThrows(IllegalArgumentException::class.java) {
                SemanticToolSelector.cosineSimilarity(a, b)
            }
        }
    }

    companion object {
        fun createTool(name: String, description: String): ToolCallback = object : ToolCallback {
            override val name = name
            override val description = description
            override suspend fun call(arguments: Map<String, Any?>): Any? = null
        }
    }
}
