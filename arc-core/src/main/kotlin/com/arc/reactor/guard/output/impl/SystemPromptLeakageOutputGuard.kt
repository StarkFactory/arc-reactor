package com.arc.reactor.guard.output.impl

import com.arc.reactor.guard.canary.CanaryTokenProvider
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 시스템 프롬프트 유출 출력 Guard (order=5)
 *
 * LLM 출력에서 시스템 프롬프트 유출을 탐지한다.
 * 두 가지 방법으로 탐지한다:
 *
 * ## 탐지 방법 1: Canary 토큰 존재 확인
 * [CanaryTokenProvider]가 시스템 프롬프트에 주입한 비밀 토큰이
 * LLM 출력에 나타나면 시스템 프롬프트가 유출된 것이다.
 * 왜 가장 효과적인가: Canary 토큰은 정상적인 LLM 응답에 절대 포함될 수 없는
 * 고유한 문자열이므로 오탐(false positive)이 거의 없다.
 *
 * ## 탐지 방법 2: 유출 패턴 매칭
 * "Here is my system prompt", "My original instructions are" 등
 * 시스템 프롬프트를 드러내는 일반적인 표현 패턴을 정규식으로 탐지한다.
 * Canary 토큰이 없는 환경에서의 폴백 방어선이다.
 *
 * ## Order가 5인 이유
 * 가장 먼저 실행되는 출력 Guard이다. 시스템 프롬프트 유출은
 * 보안 위협 중 가장 심각하므로 다른 출력 Guard보다 우선 실행한다.
 *
 * @param canaryTokenProvider Canary 토큰 제공자 (선택사항, null이면 패턴 매칭만 사용)
 *
 * @see com.arc.reactor.guard.canary.CanaryTokenProvider Canary 토큰 생성 및 검증
 * @see com.arc.reactor.guard.canary.CanarySystemPromptPostProcessor 시스템 프롬프트에 토큰 주입
 * @see com.arc.reactor.guard.output.OutputGuardStage 출력 Guard 단계 인터페이스
 */
class SystemPromptLeakageOutputGuard(
    private val canaryTokenProvider: CanaryTokenProvider? = null
) : OutputGuardStage {

    override val stageName = "SystemPromptLeakage"
    override val order = 5

    override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        // ── 검사 1: Canary 토큰 유출 확인 ──
        if (canaryTokenProvider != null && canaryTokenProvider.containsToken(content)) {
            logger.warn { "Canary token detected in output — system prompt leakage" }
            return OutputGuardResult.Rejected(
                reason = "System prompt leakage detected (canary token found)",
                category = OutputRejectionCategory.POLICY_VIOLATION,
                stage = stageName
            )
        }

        // ── 검사 2: 유출 패턴 매칭 ──
        for (pattern in LEAKAGE_PATTERNS) {
            if (pattern.containsMatchIn(content)) {
                logger.warn { "System prompt leakage pattern detected: ${pattern.pattern}" }
                return OutputGuardResult.Rejected(
                    reason = "Potential system prompt leakage detected",
                    category = OutputRejectionCategory.POLICY_VIOLATION,
                    stage = stageName
                )
            }
        }

        return OutputGuardResult.Allowed.DEFAULT
    }

    companion object {
        /**
         * 시스템 프롬프트 유출 패턴 목록.
         * LLM이 시스템 프롬프트 내용을 노출할 때 흔히 사용하는 표현들이다.
         * companion object에 정의하여 hot path에서의 재컴파일을 방지한다.
         */
        private val LEAKAGE_PATTERNS = listOf(
            // "My system prompt is/says/reads/contains/was..."
            Regex("(?i)my (full |complete |actual |real )?system prompt (is|says|reads|contains|was)"),
            // "Here is/are my system prompt/instructions..."
            Regex("(?i)here (is|are) my (full |complete |original |initial )?(system )?(prompt|instructions)"),
            // "My original instructions are/say/tell/read..."
            Regex("(?i)my (original|initial) (system )?(prompt|instructions) (are|say|tell|read)"),
            // "I'm (not) supposed to reveal/share/show my prompt..."
            Regex("(?i)I('m| am) (not )?supposed to (reveal|share|show|tell|disclose).*(prompt|instructions)"),
            // "The system prompt says/reads/contains/is..."
            Regex("(?i)the (original |initial |full |complete )?system prompt (says|reads|contains|is|was)"),

            // ── 한국어 시스템 프롬프트 유출 패턴 ──
            // "시스템 프롬프트는 다음과 같습니다"
            Regex("시스템\\s*프롬프트는.*같습니다"),
            // "제가 따르는 프롬프트/지시/명령은..."
            Regex("제가\\s*따르는.*(프롬프트|지시|명령)"),
            // "제 원래 지시사항은..."
            Regex("(제|저의|나의)\\s*(원래|초기|원본).*(지시|명령|프롬프트|설정)"),

            // ── 실제 시스템 프롬프트 콘텐츠 마커 유출 ──
            // 시스템 프롬프트에 포함된 섹션 마커가 출력에 나타나면 유출 판정
            Regex("(?i)\\[Language Rule]"),
            Regex("(?i)\\[Grounding Rules]"),
            Regex("(?i)\\[Few-shot Examples]")
        )
    }
}
