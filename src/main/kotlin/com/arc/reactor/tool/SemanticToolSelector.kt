package com.arc.reactor.tool

import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Semantic Tool Selector
 *
 * Uses embedding-based cosine similarity to select relevant tools.
 * Tool descriptions are embedded and cached; the user prompt is embedded per request.
 *
 * ## How It Works
 * 1. On first call (or when tools change), embeds each tool's `name + description`
 * 2. Embeds the user prompt
 * 3. Computes cosine similarity between prompt and each tool
 * 4. Returns tools above the similarity threshold (sorted by relevance)
 * 5. Falls back to all tools if none meet the threshold
 *
 * ## Configuration
 * ```yaml
 * arc:
 *   reactor:
 *     tool-selection:
 *       strategy: semantic
 *       similarity-threshold: 0.3
 *       max-results: 10
 * ```
 *
 * ## Example
 * ```kotlin
 * val selector = SemanticToolSelector(embeddingModel)
 * val tools = selector.select("refund my order", allTools)
 * // Returns: [processRefund, checkOrder] (most relevant first)
 * ```
 *
 * @param embeddingModel Spring AI embedding model for vectorization
 * @param similarityThreshold Minimum cosine similarity to include a tool (0.0 to 1.0)
 * @param maxResults Maximum number of tools to return
 */
class SemanticToolSelector(
    private val embeddingModel: EmbeddingModel,
    private val similarityThreshold: Double = 0.3,
    private val maxResults: Int = 10
) : ToolSelector {

    /** Cache: tool name → embedding vector. Invalidated when tool list changes. */
    private val embeddingCache = ConcurrentHashMap<String, FloatArray>()

    /** Fingerprint of the last tool list (to detect changes). */
    @Volatile
    private var lastToolFingerprint: Int = 0

    override fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        if (availableTools.isEmpty()) return emptyList()
        if (availableTools.size <= maxResults) return availableTools

        return try {
            selectSemantically(prompt, availableTools)
        } catch (e: Exception) {
            logger.warn(e) { "Semantic tool selection failed, falling back to all tools" }
            availableTools
        }
    }

    private fun selectSemantically(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        // 1. Refresh cached embeddings if tool list changed
        refreshEmbeddingsIfNeeded(availableTools)

        // 2. Embed the user prompt
        val promptEmbedding = embed(prompt)

        // 3. Score each tool by cosine similarity
        val scored = availableTools.map { tool ->
            val toolEmbedding = embeddingCache[tool.name]
                ?: embed(buildToolText(tool)).also { embeddingCache[tool.name] = it }
            val similarity = cosineSimilarity(promptEmbedding, toolEmbedding)
            tool to similarity
        }

        // 4. Filter by threshold, sort by relevance, limit results
        val selected = scored
            .filter { it.second >= similarityThreshold }
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first }

        // 5. Fallback: if nothing meets threshold, return all
        if (selected.isEmpty()) {
            logger.debug { "No tools above threshold $similarityThreshold, returning all ${availableTools.size} tools" }
            return availableTools
        }

        logger.debug {
            val names = scored.sortedByDescending { it.second }.take(5)
                .joinToString { "${it.first.name}(${String.format("%.3f", it.second)})" }
            "Semantic tool selection: top scores = [$names], selected ${selected.size}/${availableTools.size}"
        }

        return selected
    }

    private fun refreshEmbeddingsIfNeeded(tools: List<ToolCallback>) {
        val fingerprint = tools.map { it.name }.sorted().hashCode()
        if (fingerprint != lastToolFingerprint) {
            embeddingCache.clear()
            // Batch-embed all tool descriptions at once
            val texts = tools.map { buildToolText(it) }
            val embeddings = embedBatch(texts)
            tools.forEachIndexed { index, tool ->
                embeddingCache[tool.name] = embeddings[index]
            }
            lastToolFingerprint = fingerprint
            logger.info { "Refreshed semantic embeddings for ${tools.size} tools" }
        }
    }

    private fun buildToolText(tool: ToolCallback): String {
        return "${tool.name}: ${tool.description}"
    }

    private fun embed(text: String): FloatArray {
        val response = embeddingModel.embed(text)
        return response
    }

    private fun embedBatch(texts: List<String>): List<FloatArray> {
        val response = embeddingModel.embed(texts)
        return response
    }

    companion object {

        /**
         * Compute cosine similarity between two vectors.
         * Returns a value between -1.0 and 1.0 (1.0 = identical direction).
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
            require(a.size == b.size) { "Vector dimensions must match: ${a.size} vs ${b.size}" }
            var dotProduct = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dotProduct += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denominator = Math.sqrt(normA) * Math.sqrt(normB)
            return if (denominator == 0.0) 0.0 else dotProduct / denominator
        }
    }
}
