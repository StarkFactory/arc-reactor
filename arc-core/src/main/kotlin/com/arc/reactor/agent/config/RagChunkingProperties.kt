package com.arc.reactor.agent.config

/**
 * Document chunking configuration for RAG ingestion.
 *
 * When enabled, long documents are split into smaller chunks before vector storage
 * to improve embedding quality and retrieval accuracy.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     rag:
 *       chunking:
 *         enabled: true
 *         chunk-size: 512
 *         min-chunk-threshold: 512
 *         overlap: 50
 * ```
 */
data class RagChunkingProperties(
    /** Enable document chunking. */
    val enabled: Boolean = true,

    /** Target chunk size in tokens (approx 4 chars = 1 token). */
    val chunkSize: Int = 512,

    /** Minimum chunk size in characters to prevent overly small chunks. */
    val minChunkSizeChars: Int = 350,

    /** Documents with estimated tokens at or below this threshold are not split. */
    val minChunkThreshold: Int = 512,

    /** Overlap tokens between adjacent chunks for context preservation. */
    val overlap: Int = 50,

    /** Preserve paragraph/sentence separators when splitting. */
    val keepSeparator: Boolean = true,

    /** Maximum number of chunks per document. */
    val maxNumChunks: Int = 100
)
