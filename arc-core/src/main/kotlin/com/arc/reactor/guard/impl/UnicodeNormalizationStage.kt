package com.arc.reactor.guard.impl

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.InjectionPatterns
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import mu.KotlinLogging

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
 * ## 정규화 로직 공유
 * 제로 너비 문자 집합, 호모글리프 매핑, 정규화 함수는 [InjectionPatterns]에 정의되어
 * 이 단계와 [com.arc.reactor.guard.tool.ToolOutputSanitizer] 양쪽에서 공유한다.
 *
 * @param maxZeroWidthRatio 허용할 최대 제로 너비 문자 비율 (기본값: 0.1 = 10%)
 *
 * @see com.arc.reactor.guard.GuardStage Guard 단계 인터페이스
 * @see GuardPipeline 정규화된 텍스트를 전파하는 파이프라인
 * @see InjectionPatterns.normalize 공유 정규화 함수
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
        val zeroWidthCount = text.count { it.code in InjectionPatterns.ZERO_WIDTH_CODEPOINTS }
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

        // ── 단계 2: 정규화 (제로 너비 제거 + NFKC + 호모글리프 치환) ──
        val normalized = InjectionPatterns.normalize(text)

        // 텍스트가 변경된 경우에만 힌트로 전달 (불필요한 복사 방지)
        return if (normalized != text) {
            logger.debug { "Unicode normalized: changed ${text.length} → ${normalized.length} chars" }
            GuardResult.Allowed(hints = listOf("normalized:$normalized"))
        } else {
            GuardResult.Allowed.DEFAULT
        }
    }
}
