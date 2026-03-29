package com.arc.reactor.guard.output.impl

import com.arc.reactor.guard.PiiPatterns
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * PII 마스킹 출력 Guard (order=10)
 *
 * LLM 응답에 포함된 개인식별정보(PII)를 탐지하여 마스킹한다.
 *
 * ## 지원 PII 패턴
 * - 주민등록번호 → "******-*******"
 * - 전화번호 (한국 휴대폰) → "***-****-****"
 * - 신용카드번호 → "****-****-****-****"
 * - 이메일 주소 → "***@***.***"
 *
 * ## 입력 Guard와의 차이
 * - 입력 PII Guard는 PII가 있으면 **차단**
 * - 이 Guard는 출력에 PII가 있으면 **마스킹하여 전달** ([OutputGuardResult.Modified])
 * - 왜 출력은 마스킹인가: LLM이 학습 데이터나 컨텍스트에서 PII를 생성할 수 있으며,
 *   이 경우 요청 자체를 차단하면 사용자 경험이 나빠지므로 마스킹이 더 적절하다
 *
 * ## 활성화 방법
 * ```yaml
 * arc:
 *   reactor:
 *     output-guard:
 *       enabled: true
 *       pii-masking-enabled: true
 * ```
 *
 * @see com.arc.reactor.guard.PiiPatterns 공유 PII 패턴 목록
 * @see com.arc.reactor.guard.PiiPatterns PII 패턴을 공유하여 입력 Guard 구현 가능
 * @see com.arc.reactor.guard.output.OutputGuardStage 출력 Guard 단계 인터페이스
 */
class PiiMaskingOutputGuard : OutputGuardStage {

    override val stageName = "PiiMasking"
    override val order = 10

    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        var masked = content
        val detectedTypes = mutableListOf<String>()

        // 모든 PII 패턴을 순회하며 탐지 및 마스킹
        for (pattern in PiiPatterns.ALL) {
            if (pattern.regex.containsMatchIn(masked)) {
                detectedTypes.add(pattern.name)
                masked = pattern.regex.replace(masked, pattern.mask)
            }
        }

        // PII가 탐지되지 않으면 원본 그대로 통과
        if (detectedTypes.isEmpty()) {
            return OutputGuardResult.Allowed.DEFAULT
        }

        // PII가 탐지되면 마스킹된 콘텐츠를 Modified로 반환
        logger.info { "PII 감지 및 마스킹 완료: ${detectedTypes.joinToString(", ")}" }
        return OutputGuardResult.Modified(
            content = masked,
            reason = "PII masked: ${detectedTypes.joinToString(", ")}"
        )
    }
}
