package com.arc.reactor.agent.model

/**
 * 에이전트 에러 코드 열거형 — 기본 영어 메시지 포함.
 *
 * 일반적인 실패 시나리오에 대한 표준화된 에러 코드를 제공한다.
 * 메시지 커스터마이징(예: 다국어 지원)에는 [ErrorMessageResolver]를 사용한다.
 *
 * ## 한국어 에러 메시지 예시 (i18n)
 * ```kotlin
 * val koreanResolver = ErrorMessageResolver { code, originalMessage ->
 *     when (code) {
 *         AgentErrorCode.RATE_LIMITED -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
 *         AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
 *         AgentErrorCode.CONTEXT_TOO_LONG -> "입력이 너무 깁니다. 내용을 줄여주세요."
 *         AgentErrorCode.TOOL_ERROR -> "도구 실행 중 오류가 발생했습니다."
 *         AgentErrorCode.UNKNOWN -> "알 수 없는 오류가 발생했습니다."
 *     }
 * }
 * ```
 *
 * @see ErrorMessageResolver 에러 메시지 커스터마이징 인터페이스
 * @see DefaultErrorMessageResolver 기본 영어 메시지 구현체
 */
enum class AgentErrorCode(val defaultMessage: String) {
    /** 속도 제한 초과 */
    RATE_LIMITED("Rate limit exceeded. Please try again later."),
    /** 요청 타임아웃 */
    TIMEOUT("Request timed out."),
    /** 컨텍스트 길이 초과 */
    CONTEXT_TOO_LONG("Input is too long. Please reduce the content."),
    /** 도구 실행 에러 */
    TOOL_ERROR("An error occurred during tool execution."),
    /** Guard에 의한 요청 거부 */
    GUARD_REJECTED("Request rejected by guard."),
    /** Hook에 의한 요청 거부 */
    HOOK_REJECTED("Request rejected by hook."),
    /** LLM의 구조화 응답 검증 실패 */
    INVALID_RESPONSE("LLM returned an invalid structured response."),
    /** 출력 가드에 의한 응답 차단 */
    OUTPUT_GUARD_REJECTED("Response blocked by output guard."),
    /** 응답이 품질 요건에 비해 너무 짧음 */
    OUTPUT_TOO_SHORT("Response is too short to meet quality requirements."),
    /** 서킷 브레이커 개방으로 인한 일시적 서비스 불가 */
    CIRCUIT_BREAKER_OPEN("Service temporarily unavailable due to repeated failures. Please try again later."),
    /** 토큰 예산 소진으로 인한 루프 조기 종료 */
    BUDGET_EXHAUSTED("Token budget exhausted. Response may be incomplete."),
    /** 계획-실행 모드에서 계획 검증 실패 (존재하지 않는 도구, 권한 없음 등) */
    PLAN_VALIDATION_FAILED("Plan validation failed. The plan contains invalid or unauthorized tools."),
    /** 알 수 없는 에러 */
    UNKNOWN("An unknown error occurred.")
}

/**
 * 에이전트 에러 코드에 대한 에러 메시지 해석기 인터페이스.
 *
 * 커스텀(예: 다국어) 에러 메시지를 제공하려면 이 인터페이스를 구현한다.
 * Spring Bean으로 등록하면 기본 영어 메시지를 재정의할 수 있다.
 *
 * @see DefaultErrorMessageResolver 기본 구현체
 */
fun interface ErrorMessageResolver {
    /**
     * 에러 코드와 원본 메시지를 기반으로 사용자에게 표시할 메시지를 생성한다.
     *
     * @param code 에이전트 에러 코드
     * @param originalMessage 예외에서 추출한 원본 에러 메시지
     * @return 사용자에게 표시할 에러 메시지
     */
    fun resolve(code: AgentErrorCode, originalMessage: String?): String
}

/**
 * [AgentErrorCode]의 기본 영어 메시지를 사용하는 기본 구현체.
 *
 * TOOL_ERROR의 경우 원본 메시지를 추가로 포함한다.
 */
class DefaultErrorMessageResolver : ErrorMessageResolver {
    override fun resolve(code: AgentErrorCode, originalMessage: String?): String {
        return when (code) {
            AgentErrorCode.TOOL_ERROR -> code.defaultMessage
            else -> code.defaultMessage
        }
    }
}
