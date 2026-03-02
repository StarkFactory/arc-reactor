package com.arc.reactor.rag.chunking

import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.TokenEstimator
import mu.KotlinLogging
import org.springframework.ai.document.Document

private val logger = KotlinLogging.logger {}

/**
 * Token-based document chunker using recursive character splitting.
 *
 * Splits documents into chunks of approximately [chunkSize] tokens
 * with [overlap] tokens of overlap between adjacent chunks. Documents below
 * [minChunkThreshold] tokens are returned unchanged.
 *
 * Uses [TokenEstimator] for language-aware token counting:
 * - Latin/ASCII: ~4 chars/token (English text)
 * - CJK (Korean/Chinese/Japanese): ~1.5 chars/token
 * - Emoji: ~1 token each
 *
 * Based on FloTorch 2026.02 benchmark findings:
 * - Recursive splitting at 512 tokens achieves 69% accuracy (rank 1)
 * - Optimal range: 400-512 tokens + 10-20% overlap
 */
class TokenBasedDocumentChunker(
    private val chunkSize: Int = 512,
    private val minChunkSizeChars: Int = 350,
    private val minChunkThreshold: Int = 512,
    private val overlap: Int = 50,
    private val keepSeparator: Boolean = true,
    private val maxNumChunks: Int = 100,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : DocumentChunker {

    override fun chunk(document: Document): List<Document> {
        val content = document.text.orEmpty()
        if (content.isBlank()) return listOf(document)

        val estimatedTokens = tokenEstimator.estimate(content)
        if (estimatedTokens <= minChunkThreshold) return listOf(document)

        val charsPerToken = content.length.toDouble() / estimatedTokens
        val chunkSizeChars = (chunkSize * charsPerToken).toInt()
        val overlapChars = (overlap * charsPerToken).toInt()
        val chunks = splitRecursive(content, chunkSizeChars, overlapChars)

        if (chunks.size <= 1) return listOf(document)

        val totalChunks = chunks.size
        logger.debug {
            "Chunked document ${document.id}: ${content.length} chars -> $totalChunks chunks"
        }

        return chunks.mapIndexed { index, chunkContent ->
            val chunkMetadata = document.metadata.toMutableMap().apply {
                put("parent_document_id", document.id)
                put("chunk_index", index)
                put("chunk_total", totalChunks)
                put("chunked", true)
            }
            Document(DocumentChunker.chunkId(document.id, index), chunkContent, chunkMetadata)
        }
    }

    private fun splitRecursive(
        text: String,
        targetSize: Int,
        overlapSize: Int
    ): List<String> {
        if (text.length <= targetSize) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length && chunks.size < maxNumChunks) {
            var end = minOf(start + targetSize, text.length)

            // Try to break at a natural boundary (paragraph > sentence > word)
            if (end < text.length) {
                end = findBreakPoint(text, start, end)
            }

            val chunk = text.substring(start, end).trim()
            if (chunk.length >= minChunkSizeChars || chunks.isEmpty()) {
                chunks.add(chunk)
            } else if (chunks.isNotEmpty()) {
                // Merge small trailing chunk with previous
                val prev = chunks.removeAt(chunks.lastIndex)
                chunks.add(prev + "\n" + chunk)
            }

            // Advance with overlap — ensure forward progress
            val nextStart = end - overlapSize
            start = if (nextStart <= start) end else nextStart
        }

        return chunks
    }

    private fun findBreakPoint(text: String, start: Int, end: Int): Int {
        val searchFrom = start + (end - start) / 2 // Don't break in first half

        // Try paragraph break (\n\n)
        val paragraphBreak = text.lastIndexOf("\n\n", end)
        if (paragraphBreak > searchFrom) {
            return if (keepSeparator) paragraphBreak else paragraphBreak + 2
        }

        // Try line break (\n)
        val lineBreak = text.lastIndexOf('\n', end)
        if (lineBreak > searchFrom) {
            return if (keepSeparator) lineBreak else lineBreak + 1
        }

        // Try sentence break (. ! ?)
        for (i in end downTo searchFrom) {
            if (i < text.length && text[i] in SENTENCE_ENDS && i + 1 < text.length &&
                text[i + 1].isWhitespace()
            ) {
                return i + 1
            }
        }

        // Try word break (space)
        val spaceBreak = text.lastIndexOf(' ', end)
        if (spaceBreak > searchFrom) return spaceBreak + 1

        return end
    }

    companion object {
        private val SENTENCE_ENDS = charArrayOf('.', '!', '?', '。', '！', '？')
    }
}
