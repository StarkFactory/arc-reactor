package com.arc.reactor.memory

import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

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
 * Default token estimator using character-type-aware heuristics with
 * Caffeine-backed caching (10,000 entries, 5-minute TTL) to avoid
 * repeated codePoints() traversals for the same content.
 *
 * Strings longer than [CACHE_KEY_MAX_LENGTH] are computed directly
 * without caching to prevent large strings from inflating heap usage
 * as cache keys.
 *
 * Uses different ratios for different character sets:
 * - Latin/ASCII: ~4 characters per token
 * - CJK (Chinese/Japanese/Korean): ~1.5 characters per token
 * - Other: ~3 characters per token
 */
class DefaultTokenEstimator : TokenEstimator {

    companion object {
        /**
         * Strings longer than this threshold are computed directly without caching.
         * Prevents large tool outputs / document chunks from inflating the cache
         * heap — a 10,000-entry cache of 10KB strings would retain ~100MB.
         */
        const val CACHE_KEY_MAX_LENGTH = 2_000
    }

    private val cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .build<String, Int>()

    override fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        // Skip cache for long strings to avoid retaining large heap objects as keys
        if (text.length > CACHE_KEY_MAX_LENGTH) return computeTokens(text)
        return cache.get(text) { computeTokens(it) }
    }

    private fun computeTokens(text: String): Int {
        var latinChars = 0
        var cjkChars = 0
        var emojiChars = 0
        var otherChars = 0

        text.codePoints().forEach { cp ->
            when {
                cp in 0x1F300..0x1FAFF ||  // Emoticons, symbols, pictographs
                    cp in 0x2600..0x27BF || // Miscellaneous symbols, dingbats
                    cp in 0xFE00..0xFE0F   // Variation selectors
                    -> emojiChars++
                cp in 0x4E00..0x9FFF ||    // CJK Unified Ideographs
                    cp in 0xAC00..0xD7AF || // Hangul Syllables
                    cp in 0x3040..0x309F || // Hiragana
                    cp in 0x30A0..0x30FF   // Katakana
                    -> cjkChars++
                cp <= 0x7F -> latinChars++
                else -> otherChars++
            }
        }

        val latinTokens = latinChars / 4
        val cjkTokens = (cjkChars * 2 + 1) / 3  // ~1.5 chars per token
        val emojiTokens = emojiChars             // ~1 token per emoji
        val otherTokens = otherChars / 3

        return maxOf(1, latinTokens + cjkTokens + emojiTokens + otherTokens)
    }
}
