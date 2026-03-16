package com.arc.reactor.guard.output.impl

import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 설정 기반 정규식 출력 Guard (order=20)
 *
 * 설정 파일에서 정의한 정규식 패턴으로 LLM 응답을 검사한다.
 * 각 패턴은 두 가지 동작 중 하나를 수행한다:
 * - **MASK**: 매칭된 텍스트를 `[REDACTED]`로 치환
 * - **REJECT**: 응답 전체를 차단
 *
 * ## DynamicRuleOutputGuard와의 차이
 * - [DynamicRuleOutputGuard]는 DB에 저장된 규칙을 런타임에 변경 가능
 * - 이 Guard는 application.yml에 정의된 정적 패턴을 사용
 * - 왜 둘 다 필요한가: 정적 패턴은 배포 시 확정된 필수 규칙에,
 *   동적 규칙은 운영 중 추가/변경이 필요한 규칙에 사용
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     output-guard:
 *       enabled: true
 *       custom-patterns:
 *         - pattern: "(?i)internal\\s+use\\s+only"
 *           action: REJECT
 *           name: "Internal Document Leak"
 *         - pattern: "(?i)password\\s*[:=]\\s*\\S+"
 *           action: MASK
 *           name: "Password Leak"
 * ```
 *
 * @param patterns 정규식 패턴 목록
 *
 * @see DynamicRuleOutputGuard 동적(DB 기반) 정규식 출력 Guard
 * @see com.arc.reactor.guard.output.OutputGuardStage 출력 Guard 단계 인터페이스
 */
class RegexPatternOutputGuard(
    private val patterns: List<OutputBlockPattern>
) : OutputGuardStage {

    override val stageName = "RegexPattern"
    override val order = 20

    /** 패턴을 생성 시 미리 컴파일하여 hot path에서의 재컴파일을 방지한다 */
    private val compiledPatterns: List<CompiledPattern> = patterns.map {
        CompiledPattern(
            name = it.name,
            regex = Regex(it.pattern),
            action = it.action
        )
    }

    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        var masked = content
        val maskedNames = mutableListOf<String>()

        for (pattern in compiledPatterns) {
            if (!pattern.regex.containsMatchIn(masked)) continue

            when (pattern.action) {
                PatternAction.REJECT -> {
                    // REJECT: 패턴이 매칭되면 응답 전체를 즉시 차단
                    logger.warn { "RegexPattern '${pattern.name}' matched, rejecting response" }
                    return OutputGuardResult.Rejected(
                        reason = "Response blocked: ${pattern.name}",
                        category = OutputRejectionCategory.POLICY_VIOLATION
                    )
                }
                PatternAction.MASK -> {
                    // MASK: 매칭된 부분을 [REDACTED]로 치환하고 계속 진행
                    maskedNames.add(pattern.name)
                    masked = pattern.regex.replace(masked, "[REDACTED]")
                }
            }
        }

        if (maskedNames.isEmpty()) {
            return OutputGuardResult.Allowed.DEFAULT
        }

        logger.info { "RegexPattern masked: ${maskedNames.joinToString(", ")}" }
        return OutputGuardResult.Modified(
            content = masked,
            reason = "Pattern masked: ${maskedNames.joinToString(", ")}"
        )
    }

    /** 미리 컴파일된 패턴 데이터 클래스 (내부 사용) */
    private data class CompiledPattern(val name: String, val regex: Regex, val action: PatternAction)
}

/**
 * 출력 차단/마스킹 패턴 설정
 *
 * @property name 패턴의 사람이 읽을 수 있는 이름 (로깅 및 메트릭용)
 * @property pattern 정규식 패턴 문자열
 * @property action 패턴 매칭 시 수행할 동작 (MASK 또는 REJECT)
 */
data class OutputBlockPattern(
    /** 로깅 및 메트릭용 패턴 이름 */
    val name: String = "",
    /** 정규식 패턴 문자열 */
    val pattern: String = "",
    /** 매칭 시 수행할 동작 */
    val action: PatternAction = PatternAction.MASK
)

/**
 * 패턴 매칭 시 수행할 동작
 */
enum class PatternAction {
    /** 매칭된 텍스트를 [REDACTED]로 치환 */
    MASK,
    /** 응답 전체를 차단 */
    REJECT
}
