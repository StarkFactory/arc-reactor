package com.arc.reactor.memory

/**
 * Token estimation strategy for conversation memory management.
 *
 * Provides approximate token counts for text content.
 * Used by [InMemoryConversationMemory] to enforce token limits.
 *
 * ## Custom Implementation
 * ```kotlin
 * val fixedEstimator = TokenEstimator { text -> text.length / 4 }
 * val memory = InMemoryConversationMemory(tokenEstimator = fixedEstimator)
 * ```
 *
 * @see DefaultTokenEstimator for the default heuristic implementation
 */
fun interface TokenEstimator {
    /**
     * Estimate the token count for the given text.
     *
     * @param text The text to estimate tokens for
     * @return Approximate token count
     */
    fun estimate(text: String): Int
}

/**
 * Default token estimator using character-type-aware heuristics.
 *
 * Uses different ratios for different character sets:
 * - Latin/ASCII: ~4 characters per token
 * - CJK (Chinese/Japanese/Korean): ~1.5 characters per token
 * - Other: ~3 characters per token
 */
class DefaultTokenEstimator : TokenEstimator {
    override fun estimate(text: String): Int {
        if (text.isEmpty()) return 0

        var latinChars = 0
        var cjkChars = 0
        var otherChars = 0

        for (char in text) {
            when {
                char.code in 0x4E00..0x9FFF ||  // CJK Unified Ideographs
                char.code in 0xAC00..0xD7AF ||  // Hangul Syllables
                char.code in 0x3040..0x309F ||  // Hiragana
                char.code in 0x30A0..0x30FF     // Katakana
                    -> cjkChars++
                char.code <= 0x7F -> latinChars++
                else -> otherChars++
            }
        }

        val latinTokens = latinChars / 4
        val cjkTokens = (cjkChars * 2 + 1) / 3  // ~1.5 chars per token
        val otherTokens = otherChars / 3

        return maxOf(1, latinTokens + cjkTokens + otherTokens)
    }
}
