package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand

/**
 * 출력 Guard 단계에 전달되는 컨텍스트 정보
 *
 * 현재 요청에 대한 메타데이터를 포함하여 출력 Guard 단계가
 * 상황에 맞는 판단을 내릴 수 있도록 한다.
 *
 * @property command 원본 에이전트 커맨드
 * @property toolsUsed 실행 과정에서 사용된 도구 이름 목록
 * @property durationMs 실행 소요 시간 (밀리초)
 *
 * @see OutputGuardStage 이 컨텍스트를 사용하는 출력 Guard 단계
 */
data class OutputGuardContext(
    /** 원본 에이전트 커맨드 */
    val command: AgentCommand,
    /** 실행 중 사용된 도구 목록 */
    val toolsUsed: List<String>,
    /** 실행 소요 시간 (밀리초) */
    val durationMs: Long
)

/**
 * 출력 Guard 단계의 검사 결과 (sealed class)
 *
 * 입력 Guard([com.arc.reactor.guard.model.GuardResult])와 달리
 * 3가지 결과를 가질 수 있다:
 * - [Allowed]: 콘텐츠가 안전함 — 수정 없이 통과
 * - [Modified]: 콘텐츠가 수정됨 (예: PII 마스킹) — 수정된 내용으로 계속 진행
 * - [Rejected]: 콘텐츠가 위험함 — 응답 전체를 차단
 *
 * 왜 Modified가 추가로 필요한가: 입력 Guard는 차단/통과 이분법이 적절하지만,
 * 출력 Guard는 PII 마스킹 등 "부분적으로 수정하여 전달"하는 경우가 있기 때문이다.
 *
 * @see OutputGuardStage 결과를 반환하는 출력 Guard 단계
 * @see OutputGuardPipeline 결과에 따라 흐름을 제어하는 파이프라인
 */
sealed class OutputGuardResult {

    /**
     * 콘텐츠 통과 — 수정 불필요
     *
     * @property hints 후속 단계에 전달할 힌트 목록
     */
    data class Allowed(
        val hints: List<String> = emptyList()
    ) : OutputGuardResult() {
        companion object {
            /** 힌트 없는 기본 허용 결과 싱글턴 */
            val DEFAULT = Allowed()
        }
    }

    /**
     * 콘텐츠 수정됨 (예: PII 마스킹) — 수정된 내용으로 계속 진행
     *
     * @property content 수정된 콘텐츠
     * @property reason 수정 사유 (로깅/메트릭용)
     * @property stage 수정을 수행한 단계명
     */
    data class Modified(
        val content: String,
        val reason: String,
        val stage: String? = null
    ) : OutputGuardResult()

    /**
     * 콘텐츠 위험 — 응답 전체 차단
     *
     * @property reason 차단 사유
     * @property category 차단 카테고리
     * @property stage 차단을 발생시킨 단계명
     */
    data class Rejected(
        val reason: String,
        val category: OutputRejectionCategory,
        val stage: String? = null
    ) : OutputGuardResult()
}

/**
 * 출력 Guard 거부 카테고리
 *
 * 출력 Guard에서 응답을 차단한 사유를 분류한다.
 */
enum class OutputRejectionCategory {
    /** 응답에서 PII 탐지됨 */
    PII_DETECTED,

    /** 유해하거나 독성 있는 콘텐츠 */
    HARMFUL_CONTENT,

    /** 정책 위반 (시스템 프롬프트 유출, 동적 규칙 매칭 등) */
    POLICY_VIOLATION,

    /** Guard 단계 내부 오류 (fail-close로 차단됨) */
    SYSTEM_ERROR
}
