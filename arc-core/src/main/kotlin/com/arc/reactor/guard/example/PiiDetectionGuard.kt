package com.arc.reactor.guard.example

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.PiiPatterns
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory

/**
 * PII 탐지 Guard (예제) — 정규식 기반 GuardStage
 *
 * 사용자 입력에 개인식별정보(PII)가 포함되어 있으면 요청을 차단한다.
 * 개인정보가 LLM에 전송되는 것을 사전에 방지하는 역할을 한다.
 *
 * ## 탐지 대상 PII
 * - 주민등록번호 (6자리-7자리)
 * - 전화번호 (한국 휴대폰)
 * - 신용카드번호 (4자리 x 4그룹)
 * - 이메일 주소
 *
 * ## Order가 25인 이유
 * InputValidation(2)과 InjectionDetection(3) 사이에 배치한다.
 * 입력 길이 검증은 통과했지만, Injection 탐지 전에 PII를 걸러내어
 * 개인정보가 이후 어떤 처리에도 노출되지 않도록 한다.
 *
 * ## 프로덕션 권장사항
 * 이 예제는 단순 정규식 기반이다. 프로덕션에서는:
 * - Presidio 같은 전문 라이브러리와 통합
 * - 요청 차단 대신 PII 마스킹 후 처리 허용
 * - 국가별 PII 패턴 추가
 *
 * ## 활성화 방법
 * @Component를 추가하면 GuardPipeline이 이 단계를 자동 포함한다.
 *
 * @see com.arc.reactor.guard.PiiPatterns 공유 PII 패턴 목록
 * @see com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard 출력에서의 PII 마스킹
 * @see com.arc.reactor.guard.GuardStage Guard 단계 인터페이스
 */
// @Component  ← 자동 등록하려면 주석 해제
class PiiDetectionGuard : GuardStage {

    override val stageName = "PiiDetection"

    // InputValidation(2) 이후, InjectionDetection(3) 이전
    override val order = 25

    override suspend fun check(command: GuardCommand): GuardResult {
        // 모든 PII 패턴을 순회하며 입력에 포함 여부 확인
        for (pattern in PiiPatterns.ALL) {
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
}
