package com.arc.reactor.guard.impl

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import mu.KotlinLogging
import java.text.Normalizer

private val logger = KotlinLogging.logger {}

/**
 * Unicode 정규화 단계 (Layer 0, order=0)
 *
 * Unicode 기반 Prompt Injection 우회 공격을 방어한다.
 * 모든 Guard 단계보다 먼저 실행(order=0)되어 후속 단계가
 * 깨끗한 ASCII 텍스트로 패턴 매칭할 수 있도록 전처리한다.
 *
 * ## 왜 order=0인가
 * 공격자가 "IgnΟre previΟus instructiΟns"처럼 키릴 문자 'О'를 섞으면
 * 후속 Injection 탐지 정규식이 이를 놓칠 수 있다.
 * 정규화를 가장 먼저 수행하여 이런 우회를 차단한다.
 *
 * ## 방어 대상
 * 1. **NFKC 정규화**: 전각 라틴 → ASCII, 호환성 분해 (예: ＡＢＣ → ABC)
 * 2. **제로 너비 문자 제거**: U+200B~F, U+FEFF, U+00AD, U+2060~2064, U+180E, Unicode Tag Block
 * 3. **호모글리프 치환**: 키릴 문자 → 라틴 유사 문자 (예: 키릴 'а' → 라틴 'a')
 * 4. **과도한 제로 너비 문자 거부**: 전체 코드포인트 대비 10% 이상이면 거부
 *
 * ## 정규화된 텍스트 전파
 * 텍스트가 변경되면 "normalized:{텍스트}" 힌트를 반환한다.
 * [GuardPipeline]이 이 힌트를 감지하여 후속 단계에 정규화된 텍스트를 전달한다.
 *
 * @param maxZeroWidthRatio 허용할 최대 제로 너비 문자 비율 (기본값: 0.1 = 10%)
 *
 * @see com.arc.reactor.guard.GuardStage Guard 단계 인터페이스
 * @see GuardPipeline 정규화된 텍스트를 전파하는 파이프라인
 */
class UnicodeNormalizationStage(
    private val maxZeroWidthRatio: Double = 0.1
) : GuardStage {

    override val stageName = "UnicodeNormalization"
    override val order = 0

    override suspend fun check(command: GuardCommand): GuardResult {
        val text = command.text
        if (text.isEmpty()) return GuardResult.Allowed.DEFAULT

        // ── 단계 1: 제로 너비 문자 비율 검사 ──
        // 공격자가 제로 너비 문자를 대량 삽입하여 텍스트를 숨기는 공격을 방어한다
        val zeroWidthCount = text.count { it.code in ZERO_WIDTH_CODEPOINTS }
        // Unicode Tag Block (U+E0000~U+E007F)은 보조 평면에 있어 별도로 카운트
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

        // ── 단계 2: 제로 너비 문자 제거 ──
        val stripped = stripZeroWidthChars(text)

        // ── 단계 3: NFKC 정규화 ──
        // 전각 문자 → ASCII, 호환성 분해 후 합성
        // 왜 NFKC인가: NFC는 호환성 분해를 하지 않아 전각 문자를 잡지 못한다
        val nfkc = Normalizer.normalize(stripped, Normalizer.Form.NFKC)

        // ── 단계 4: 호모글리프 치환 ──
        // 키릴 문자 등 시각적으로 유사한 문자를 라틴 문자로 치환
        val normalized = replaceHomoglyphs(nfkc)

        // 텍스트가 변경된 경우에만 힌트로 전달 (불필요한 복사 방지)
        return if (normalized != text) {
            logger.debug { "Unicode normalized: changed ${text.length} → ${normalized.length} chars" }
            GuardResult.Allowed(hints = listOf("normalized:$normalized"))
        } else {
            GuardResult.Allowed.DEFAULT
        }
    }

    companion object {
        /**
         * 제거 대상 제로 너비 문자 코드포인트 집합.
         * 이 문자들은 화면에 보이지 않지만 정규식 패턴 매칭을 방해할 수 있다.
         */
        private val ZERO_WIDTH_CODEPOINTS = setOf(
            0x200B, // Zero Width Space — 단어 사이에 숨겨진 공백
            0x200C, // Zero Width Non-Joiner — 합자 방지 (아랍어/힌디어용이지만 우회에 악용)
            0x200D, // Zero Width Joiner — 합자 강제 (이모지 조합용이지만 우회에 악용)
            0x200E, // Left-to-Right Mark — 양방향 텍스트 제어
            0x200F, // Right-to-Left Mark — 양방향 텍스트 제어
            0xFEFF, // Zero Width No-Break Space (BOM) — 파일 시작 마커이지만 중간에 삽입 가능
            0x00AD, // Soft Hyphen — 줄바꿈 힌트지만 패턴 분리에 악용
            0x2060, // Word Joiner — 줄바꿈 방지
            0x2061, // Function Application — 수학 표기용이지만 텍스트에 숨기기 가능
            0x2062, // Invisible Times — 수학 표기용
            0x2063, // Invisible Separator — 수학 표기용
            0x2064, // Invisible Plus — 수학 표기용
            0x180E  // Mongolian Vowel Separator — 몽골 문자용이지만 우회에 악용
        )

        /**
         * 키릴 문자 → 라틴 문자 호모글리프 매핑.
         *
         * 왜 키릴 문자만 매핑하는가: 키릴 문자는 라틴 문자와 시각적으로 가장 유사한
         * 문자가 많아 Prompt Injection 우회에 가장 자주 사용된다.
         * 예: 키릴 'а' (U+0430)와 라틴 'a' (U+0061)는 육안으로 구분 불가
         */
        private val HOMOGLYPH_MAP = mapOf(
            '\u0430' to 'a', // 키릴 а → 라틴 a
            '\u0435' to 'e', // 키릴 е → 라틴 e
            '\u043E' to 'o', // 키릴 о → 라틴 o
            '\u0440' to 'p', // 키릴 р → 라틴 p
            '\u0441' to 'c', // 키릴 с → 라틴 c
            '\u0443' to 'y', // 키릴 у → 라틴 y
            '\u0445' to 'x', // 키릴 х → 라틴 x
            '\u0410' to 'A', // 키릴 А → 라틴 A
            '\u0412' to 'B', // 키릴 В → 라틴 B
            '\u0415' to 'E', // 키릴 Е → 라틴 E
            '\u041A' to 'K', // 키릴 К → 라틴 K
            '\u041C' to 'M', // 키릴 М → 라틴 M
            '\u041D' to 'H', // 키릴 Н → 라틴 H
            '\u041E' to 'O', // 키릴 О → 라틴 O
            '\u0420' to 'P', // 키릴 Р → 라틴 P
            '\u0421' to 'C', // 키릴 С → 라틴 C
            '\u0422' to 'T', // 키릴 Т → 라틴 T
            '\u0425' to 'X'  // 키릴 Х → 라틴 X
        )

        /**
         * 텍스트에서 제로 너비 문자와 Unicode Tag Block 문자를 제거한다.
         * 보조 평면 코드포인트를 올바르게 처리하기 위해 codePointAt을 사용한다.
         */
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

        /**
         * 텍스트 내 호모글리프(시각적 유사 문자)를 라틴 문자로 치환한다.
         */
        private fun replaceHomoglyphs(text: String): String {
            val sb = StringBuilder(text.length)
            for (char in text) {
                sb.append(HOMOGLYPH_MAP[char] ?: char)
            }
            return sb.toString()
        }
    }
}
