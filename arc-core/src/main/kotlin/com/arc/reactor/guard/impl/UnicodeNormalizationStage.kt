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
 * 3. **HTML 수치 엔티티 디코딩**: &#73; → I, &#x49; → I (엔티티 기반 우회 차단)
 * 4. **호모글리프 치환**: 키릴 문자 → 라틴 유사 문자 (예: 키릴 'а' → 라틴 'a')
 * 5. **과도한 제로 너비 문자 거부**: 전체 코드포인트 대비 10% 이상이면 거부
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

    override suspend fun enforce(command: GuardCommand): GuardResult {
        val text = command.text
        if (text.isEmpty()) return GuardResult.Allowed.DEFAULT

        // ── 단계 1+2 통합: 제로 너비 카운트 + 스트립을 단일 패스로 수행 ──
        // 기존: codePoints() 순회(카운트) + normalize() 내부 stripZeroWidthChars()(스트립) = 2회 순회
        // 최적화: 1회 순회로 카운트와 스트립을 동시 수행하여 핫 패스 부하 감소
        var totalZeroWidth = 0
        var totalCodepoints = 0
        val stripped = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            totalCodepoints++
            if (cp in InjectionPatterns.ZERO_WIDTH_CODEPOINTS || cp in 0xE0000..0xE007F) {
                totalZeroWidth++
            } else {
                stripped.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }

        if (totalZeroWidth > 0) {
            val ratio = totalZeroWidth.toDouble() / totalCodepoints
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

        // 제로 너비 문자가 있었으면 이미 스트립된 결과 사용, 없으면 원본 그대로
        val preStripped = if (totalZeroWidth > 0) stripped.toString() else text
        val normalized = InjectionPatterns.normalizePreStripped(preStripped)

        // 텍스트가 변경된 경우에만 힌트로 전달 (불필요한 복사 방지)
        return if (normalized != text) {
            logger.debug { "유니코드 정규화: ${text.length} → ${normalized.length}자로 변환" }
            GuardResult.Allowed(hints = listOf("normalized:$normalized"))
        } else {
            GuardResult.Allowed.DEFAULT
        }
    }
}
