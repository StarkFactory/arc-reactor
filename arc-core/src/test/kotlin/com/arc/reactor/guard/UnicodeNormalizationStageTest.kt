package com.arc.reactor.guard

import com.arc.reactor.guard.impl.UnicodeNormalizationStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 유니코드 정규화 단계에 대한 테스트.
 *
 * 유니코드 정규화 가드의 동작을 검증합니다.
 */
class UnicodeNormalizationStageTest {

    private val stage = UnicodeNormalizationStage()

    private fun command(text: String) = GuardCommand(userId = "user-1", text = text)

    @Nested
    inner class NfkcNormalization {

        @Test
        fun `fullwidth Latin characters은(는) normalized to ASCII이다`() = runBlocking {
            // ｉｇｎｏｒｅ → ignore
            val result = stage.enforce(command("\uFF49\uFF47\uFF4E\uFF4F\uFF52\uFF45"))
            val allowed = assertInstanceOf(GuardResult.Allowed::class.java, result)
            val normalized = allowed.hints.firstOrNull { it.startsWith("normalized:") }
                ?.removePrefix("normalized:")
            assertEquals("ignore", normalized, "Fullwidth Latin should be normalized to ASCII")
        }

        @Test
        fun `normal ASCII 텍스트는 변경 없이 통과한다`() = runBlocking {
            val result = stage.enforce(command("Hello world, how are you?"))
            assertEquals(GuardResult.Allowed.DEFAULT, result,
                "Normal ASCII text should not be modified")
        }
    }

    @Nested
    inner class HomoglyphReplacement {

        @Test
        fun `Cyrillic homoglyphs은(는) replaced with Latin equivalents이다`() = runBlocking {
            // Cyrillic а (U+0430) in "ignore" →은(는) normalize to Latin 'a'해야 합니다
            val cyrillicA = '\u0430'
            val text = "${cyrillicA}bcd"
            val result = stage.enforce(command(text))
            val allowed = assertInstanceOf(GuardResult.Allowed::class.java, result)
            val normalized = allowed.hints.firstOrNull { it.startsWith("normalized:") }
                ?.removePrefix("normalized:")
            assertEquals("abcd", normalized,
                "Cyrillic 'а' should be replaced with Latin 'a'")
        }

        @Test
        fun `mixed Cyrillic and Latin in injection attempt은(는) normalized이다`() = runBlocking {
            // "ignоre" with Cyrillic о (U+043E) instead of Latin o
            val text = "ign\u043Ere"
            val result = stage.enforce(command(text))
            val allowed = assertInstanceOf(GuardResult.Allowed::class.java, result)
            val normalized = allowed.hints.firstOrNull { it.startsWith("normalized:") }
                ?.removePrefix("normalized:")
            assertEquals("ignore", normalized,
                "Cyrillic homoglyph should be replaced so downstream catches injection")
        }
    }

    @Nested
    inner class ZeroWidthStripping {

        // 작은 테스트 문자열이 거부되지 않도록 완화된 임계값 사용
        private val relaxedStage = UnicodeNormalizationStage(maxZeroWidthRatio = 0.9)

        @Test
        fun `zero-width characters between words은(는) stripped이다`() = runBlocking {
            // "ignore" with U+200B between each char
            val text = "i\u200Bg\u200Bn\u200Bo\u200Br\u200Be"
            val result = relaxedStage.enforce(command(text))
            val allowed = assertInstanceOf(GuardResult.Allowed::class.java, result,
                "Should allow with relaxed threshold and strip zero-width chars")
            val normalized = allowed.hints.firstOrNull { it.startsWith("normalized:") }
                ?.removePrefix("normalized:")
            assertEquals("ignore", normalized,
                "Zero-width chars should be stripped so downstream catches injection")
        }

        @Test
        fun `Unicode Tag Block characters은(는) stripped이다`() = runBlocking {
            // 태그 블록(U+E0001, U+E0020)의 보충 코드포인트를 위해 서로게이트 쌍 사용
            val tagChar1 = String(Character.toChars(0xE0001))
            val tagChar2 = String(Character.toChars(0xE0020))
            val text = "a".repeat(30) + tagChar1 + tagChar2 + "hello"
            val result = relaxedStage.enforce(command(text))
            val allowed = assertInstanceOf(GuardResult.Allowed::class.java, result,
                "Should strip Tag Block chars when ratio is acceptable")
            val normalized = allowed.hints.firstOrNull { it.startsWith("normalized:") }
                ?.removePrefix("normalized:")
            assertNotNull(normalized, "Should produce normalized hint")
            assertTrue(normalized!!.contains("hello"),
                "Visible text should be preserved")
            assertFalse(normalized.codePoints().anyMatch { it in 0xE0000..0xE007F },
                "Tag Block characters should be stripped from output")
        }

        @Test
        fun `soft hyphen은(는) stripped이다`() = runBlocking {
            val text = "ig\u00ADnore"
            val result = relaxedStage.enforce(command(text))
            val allowed = assertInstanceOf(GuardResult.Allowed::class.java, result,
                "Should strip soft hyphen")
            val normalized = allowed.hints.firstOrNull { it.startsWith("normalized:") }
                ?.removePrefix("normalized:")
            assertEquals("ignore", normalized,
                "Soft hyphen should be stripped")
        }
    }

    @Nested
    inner class ZeroWidthRatioRejection {

        @Test
        fun `high zero-width ratio exceeding threshold은(는) rejected이다`() = runBlocking {
            // 2 visible chars, many zero-width → ratio > 10%
            val text = "ab" + "\u200B".repeat(20)
            val result = stage.enforce(command(text))
            val rejected = assertInstanceOf(GuardResult.Rejected::class.java, result)
            assertEquals(RejectionCategory.PROMPT_INJECTION, rejected.category,
                "Excessive zero-width characters should be rejected as injection")
        }

        @Test
        fun `acceptable은(는) zero-width ratio passes`() = runBlocking {
            // 100 visible chars, 5 zero-width → 5% ratio, under 10%
            val text = "a".repeat(100) + "\u200B".repeat(5)
            val result = stage.enforce(command(text))
            assertInstanceOf(GuardResult.Allowed::class.java, result,
                "Acceptable zero-width ratio should pass")
        }
    }

    @Nested
    inner class CjkPreservation {

        @Test
        fun `Korean 텍스트는 변경 없이 통과한다`() = runBlocking {
            val result = stage.enforce(command("안녕하세요, 오늘 날씨 어때요?"))
            assertEquals(GuardResult.Allowed.DEFAULT, result,
                "Korean text should pass unchanged")
        }

        @Test
        fun `Chinese 텍스트는 변경 없이 통과한다`() = runBlocking {
            val result = stage.enforce(command("你好世界"))
            assertEquals(GuardResult.Allowed.DEFAULT, result,
                "Chinese text should pass unchanged")
        }

        @Test
        fun `Japanese 텍스트는 변경 없이 통과한다`() = runBlocking {
            val result = stage.enforce(command("こんにちは世界"))
            assertEquals(GuardResult.Allowed.DEFAULT, result,
                "Japanese text should pass unchanged")
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `비어있는 input passes as DEFAULT`() = runBlocking {
            val result = stage.enforce(command(""))
            assertEquals(GuardResult.Allowed.DEFAULT, result,
                "Empty input should return DEFAULT")
        }

        @Test
        fun `order은(는) 0이다`() {
            assertEquals(0, stage.order, "UnicodeNormalization should execute before all stages (order=0)")
        }

        @Test
        fun `stage name은(는) correct이다`() {
            assertEquals("UnicodeNormalization", stage.stageName)
        }
    }
}
