package com.arc.reactor.guard.impl

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import mu.KotlinLogging
import java.text.Normalizer

private val logger = KotlinLogging.logger {}

/**
 * Unicode Normalization Stage (Layer 0, order=0)
 *
 * Defends against Unicode-based prompt injection evasion:
 * - NFKC normalization (fullwidth Latin → ASCII, compatibility decomposition)
 * - Zero-width character stripping (U+200B-F, U+FEFF, U+00AD, U+2060-2064, U+180E, Unicode Tag Block)
 * - Homoglyph replacement (Cyrillic → Latin lookalikes)
 * - Rejects input with excessive zero-width character ratio (>10%)
 *
 * Returns normalized text via hints so downstream stages can match against clean ASCII.
 */
class UnicodeNormalizationStage(
    private val maxZeroWidthRatio: Double = 0.1
) : GuardStage {

    override val stageName = "UnicodeNormalization"
    override val order = 0

    override suspend fun check(command: GuardCommand): GuardResult {
        val text = command.text
        if (text.isEmpty()) return GuardResult.Allowed.DEFAULT

        // Count zero-width characters before stripping (handle supplementary codepoints)
        val zeroWidthCount = text.count { it.code in ZERO_WIDTH_CODEPOINTS }
        var tagBlockCount = 0
        text.codePoints().forEach { cp ->
            if (cp in 0xE0000..0xE007F) tagBlockCount++
        }
        val totalZeroWidth = zeroWidthCount + tagBlockCount

        if (totalZeroWidth > 0 && text.isNotEmpty()) {
            val codepointCount = text.codePointCount(0, text.length)
            val ratio = totalZeroWidth.toDouble() / codepointCount
            if (ratio > maxZeroWidthRatio) {
                logger.warn {
                    "Zero-width character ratio ${String.format("%.2f", ratio)} " +
                        "exceeds threshold $maxZeroWidthRatio"
                }
                return GuardResult.Rejected(
                    reason = "Input contains excessive invisible characters",
                    category = RejectionCategory.PROMPT_INJECTION
                )
            }
        }

        // Step 1: Strip zero-width characters
        val stripped = stripZeroWidthChars(text)

        // Step 2: NFKC normalization (fullwidth → ASCII, compatibility decomposition)
        val nfkc = Normalizer.normalize(stripped, Normalizer.Form.NFKC)

        // Step 3: Homoglyph replacement (Cyrillic → Latin)
        val normalized = replaceHomoglyphs(nfkc)

        // Only propagate if text changed
        return if (normalized != text) {
            logger.debug { "Unicode normalized: changed ${text.length} → ${normalized.length} chars" }
            GuardResult.Allowed(hints = listOf("normalized:$normalized"))
        } else {
            GuardResult.Allowed.DEFAULT
        }
    }

    companion object {
        // Zero-width character codepoints to strip
        private val ZERO_WIDTH_CODEPOINTS = setOf(
            0x200B, // Zero Width Space
            0x200C, // Zero Width Non-Joiner
            0x200D, // Zero Width Joiner
            0x200E, // Left-to-Right Mark
            0x200F, // Right-to-Left Mark
            0xFEFF, // Zero Width No-Break Space (BOM)
            0x00AD, // Soft Hyphen
            0x2060, // Word Joiner
            0x2061, // Function Application
            0x2062, // Invisible Times
            0x2063, // Invisible Separator
            0x2064, // Invisible Plus
            0x180E  // Mongolian Vowel Separator
        )

        // Cyrillic → Latin homoglyph mappings
        private val HOMOGLYPH_MAP = mapOf(
            '\u0430' to 'a', // Cyrillic а → Latin a
            '\u0435' to 'e', // Cyrillic е → Latin e
            '\u043E' to 'o', // Cyrillic о → Latin o
            '\u0440' to 'p', // Cyrillic р → Latin p
            '\u0441' to 'c', // Cyrillic с → Latin c
            '\u0443' to 'y', // Cyrillic у → Latin y
            '\u0445' to 'x', // Cyrillic х → Latin x
            '\u0410' to 'A', // Cyrillic А → Latin A
            '\u0412' to 'B', // Cyrillic В → Latin B
            '\u0415' to 'E', // Cyrillic Е → Latin E
            '\u041A' to 'K', // Cyrillic К → Latin K
            '\u041C' to 'M', // Cyrillic М → Latin M
            '\u041D' to 'H', // Cyrillic Н → Latin H
            '\u041E' to 'O', // Cyrillic О → Latin O
            '\u0420' to 'P', // Cyrillic Р → Latin P
            '\u0421' to 'C', // Cyrillic С → Latin C
            '\u0422' to 'T', // Cyrillic Т → Latin T
            '\u0425' to 'X'  // Cyrillic Х → Latin X
        )

        private fun stripZeroWidthChars(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (cp !in ZERO_WIDTH_CODEPOINTS && cp !in 0xE0000..0xE007F) {
                    sb.appendCodePoint(cp)
                }
                i += Character.charCount(cp)
            }
            return sb.toString()
        }

        private fun replaceHomoglyphs(text: String): String {
            val sb = StringBuilder(text.length)
            for (char in text) {
                sb.append(HOMOGLYPH_MAP[char] ?: char)
            }
            return sb.toString()
        }
    }
}
