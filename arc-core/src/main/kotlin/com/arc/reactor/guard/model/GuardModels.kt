package com.arc.reactor.guard.model

/**
 * Guard 검사 요청 커맨드
 *
 * Guard 파이프라인에 전달되는 요청 데이터를 캡슐화한다.
 * [com.arc.reactor.guard.impl.GuardPipeline]이 이 커맨드를 받아
 * 각 [com.arc.reactor.guard.GuardStage]에 순차적으로 전달한다.
 *
 * @property userId 요청한 사용자 ID (속도 제한 키로 사용). null 불가 — "anonymous" 폴백 필수
 * @property text 검증할 사용자 입력 텍스트
 * @property channel 요청 채널 (예: "slack", "web"). 채널별 정책 적용에 사용
 * @property metadata 확장 메타데이터 (tenantId, sessionId 등). Guard 단계가 자유롭게 참조 가능
 * @property systemPrompt 시스템 프롬프트 (길이 검증 대상)
 *
 * @see com.arc.reactor.guard.RequestGuard Guard 인터페이스
 */
data class GuardCommand(
    val userId: String,
    val text: String,
    val channel: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val systemPrompt: String? = null
)

/**
 * Guard 검사 결과 (sealed class)
 *
 * Guard 파이프라인의 각 단계는 이 결과를 반환한다.
 * - [Allowed]: 요청 통과 — 다음 단계로 진행
 * - [Rejected]: 요청 거부 — 파이프라인 즉시 중단
 *
 * @see com.arc.reactor.guard.GuardStage 결과를 반환하는 Guard 단계
 * @see com.arc.reactor.guard.impl.GuardPipeline 결과에 따라 흐름을 제어하는 파이프라인
 */
sealed class GuardResult {
    /**
     * 요청 허용 결과
     *
     * @property hints 후속 단계에 전달할 힌트 목록.
     *   예: UnicodeNormalizationStage가 "normalized:{정규화된 텍스트}"를 전달하면
     *   파이프라인이 이후 단계에서 정규화된 텍스트를 사용한다.
     */
    data class Allowed(
        val hints: List<String> = emptyList()
    ) : GuardResult() {
        companion object {
            /** 힌트 없는 기본 허용 결과 싱글턴 */
            val DEFAULT = Allowed()
        }
    }

    /**
     * 요청 거부 결과
     *
     * @property reason 거부 사유 (사용자에게 표시될 수 있음)
     * @property category 거부 카테고리 (메트릭/분석용)
     * @property stage 거부를 발생시킨 Guard 단계명
     */
    data class Rejected(
        val reason: String,
        val category: RejectionCategory,
        val stage: String? = null
    ) : GuardResult()
}

/**
 * 거부 카테고리 열거형
 *
 * Guard 거부 사유를 분류하여 메트릭 수집, 대시보드 표시,
 * 알림 라우팅 등에 활용한다.
 */
enum class RejectionCategory {
    /** 속도 제한 초과 — 분당/시간당 요청 한도 도달 */
    RATE_LIMITED,

    /** 잘못된 입력 — 길이, 형식 등 검증 실패 */
    INVALID_INPUT,

    /** Prompt Injection 탐지 — 악의적 패턴 발견 */
    PROMPT_INJECTION,

    /** 주제 벗어남 — 허용된 토픽 범위 밖의 요청 */
    OFF_TOPIC,

    /** 권한 없음 — 사용자에게 해당 작업 권한 없음 */
    UNAUTHORIZED,

    /** 시스템 오류 — Guard 단계 내부 오류 (fail-close로 거부됨) */
    SYSTEM_ERROR
}
