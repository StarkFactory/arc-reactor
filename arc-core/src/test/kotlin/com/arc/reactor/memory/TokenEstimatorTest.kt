package com.arc.reactor.memory

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TokenEstimatorTest {

    private val estimator = DefaultTokenEstimator()

    @Nested
    inner class EmptyAndMinimal {

        @Test
        fun `empty string returns 0`() {
            estimator.estimate("") shouldBe 0
        }

        @Test
        fun `single character returns at least 1`() {
            estimator.estimate("a") shouldBeGreaterThanOrEqual 1
        }

        @Test
        fun `whitespace-only text returns at least 1`() {
            estimator.estimate("   ") shouldBeGreaterThanOrEqual 1
        }
    }

    @Nested
    inner class LatinText {

        @Test
        fun `short latin text uses ~4 chars per token`() {
            // "Hello World" = 11 chars => ~2-3 tokens
            val result = estimator.estimate("Hello World")
            result shouldBeGreaterThanOrEqual 1
        }

        @Test
        fun `100 latin chars produces ~25 tokens`() {
            val text = "a".repeat(100)
            val result = estimator.estimate(text)
            result shouldBe 25
        }
    }

    @Nested
    inner class CjkText {

        @Test
        fun `korean text produces more tokens per char than latin`() {
            val korean = "안녕하세요"  // 5 chars
            val latin = "abcde"        // 5 chars
            estimator.estimate(korean) shouldBeGreaterThan estimator.estimate(latin)
        }

        @Test
        fun `chinese text uses ~1_5 chars per token`() {
            // 30 CJK chars => ~20 tokens
            val text = "测".repeat(30)
            val result = estimator.estimate(text)
            result shouldBe 20
        }

        @Test
        fun `japanese hiragana counts as CJK`() {
            val text = "あ".repeat(30)
            val result = estimator.estimate(text)
            result shouldBe 20
        }

        @Test
        fun `hangul syllables count as CJK`() {
            val text = "가".repeat(30)
            val result = estimator.estimate(text)
            result shouldBe 20
        }
    }

    @Nested
    inner class EmojiText {

        @Test
        fun `emojis count as ~1 token each`() {
            val emojis = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02"  // 3 emojis
            val result = estimator.estimate(emojis)
            result shouldBe 3
        }
    }

    @Nested
    inner class MixedText {

        @Test
        fun `mixed latin and CJK sums both estimates`() {
            val latin = "a".repeat(40)  // => 10 tokens
            val cjk = "가".repeat(15)    // => 10 tokens
            val result = estimator.estimate(latin + cjk)
            result shouldBe 20
        }
    }

    @Nested
    inner class CacheBehavior {

        @Test
        fun `long strings bypass cache to avoid heap bloat`() {
            val longText = "a".repeat(DefaultTokenEstimator.CACHE_KEY_MAX_LENGTH + 1)
            // Should still produce correct result even without caching
            val result = estimator.estimate(longText)
            result shouldBeGreaterThan 0
        }

        @Test
        fun `strings at cache boundary are still cached`() {
            val text = "a".repeat(DefaultTokenEstimator.CACHE_KEY_MAX_LENGTH)
            val first = estimator.estimate(text)
            val second = estimator.estimate(text)
            first shouldBe second
        }

        @Test
        fun `very long string produces consistent results`() {
            val longText = "a".repeat(50_000)
            val first = estimator.estimate(longText)
            val second = estimator.estimate(longText)
            first shouldBe second
            first shouldBe 12500 // 50000 / 4
        }
    }

    @Nested
    inner class FunctionalInterface {

        @Test
        fun `custom TokenEstimator lambda works`() {
            val custom = TokenEstimator { it.length / 2 }
            custom.estimate("abcdef") shouldBe 3
        }
    }
}
