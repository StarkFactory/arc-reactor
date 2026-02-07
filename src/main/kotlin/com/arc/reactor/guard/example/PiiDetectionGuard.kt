package com.arc.reactor.guard.example

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory

/**
 * 개인정보(PII) 탐지 Guard (예시) — 정규식 기반 GuardStage
 *
 * 사용자 입력에 개인정보(이메일, 전화번호, 주민등록번호 등)가 포함되어 있으면
 * 요청을 거부하여 개인정보가 LLM에 전달되는 것을 방지합니다.
 *
 * ## 주의사항
 * 이 예시는 간단한 정규식 기반입니다. 프로덕션에서는 다음을 고려하세요:
 * - 더 정교한 패턴 매칭 (Presidio 등 전문 라이브러리 연동)
 * - PII를 차단하는 대신 마스킹하여 전달하는 방식
 * - 국가별 개인정보 패턴 추가
 *
 * ## 활성화 방법
 * @Component를 추가하면 자동 등록됩니다.
 */
// @Component  ← 주석 해제하면 자동 등록
class PiiDetectionGuard : GuardStage {

    override val stageName = "PiiDetection"

    // InputValidation(2) 이후, InjectionDetection(3) 이전
    override val order = 25

    override suspend fun check(command: GuardCommand): GuardResult {
        for (pattern in PII_PATTERNS) {
            if (pattern.regex.containsMatchIn(command.text)) {
                return GuardResult.Rejected(
                    reason = "개인정보(${pattern.name})가 포함된 요청은 처리할 수 없습니다. " +
                        "개인정보를 제거한 후 다시 시도해주세요.",
                    category = RejectionCategory.INVALID_INPUT,
                    stage = stageName
                )
            }
        }
        return GuardResult.Allowed.DEFAULT
    }

    private data class PiiPattern(val name: String, val regex: Regex)

    companion object {
        private val PII_PATTERNS = listOf(
            // 한국 주민등록번호 (000000-0000000)
            PiiPattern(
                "주민등록번호",
                Regex("""\d{6}\s?-\s?[1-4]\d{6}""")
            ),
            // 한국 전화번호 (010-0000-0000)
            PiiPattern(
                "전화번호",
                Regex("""01[016789]-?\d{3,4}-?\d{4}""")
            ),
            // 신용카드 번호 (0000-0000-0000-0000)
            PiiPattern(
                "신용카드번호",
                Regex("""\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}""")
            ),
            // 이메일 주소
            PiiPattern(
                "이메일",
                Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""")
            )
        )
    }
}
