package com.arc.reactor.errorreport.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 오류 리포트 모듈 설정 프로퍼티.
 *
 * 프리픽스: `arc.reactor.error-report`
 *
 * @see ErrorReportAutoConfiguration 자동 설정
 * @see ErrorReportController REST 엔드포인트
 */
@ConfigurationProperties(prefix = "arc.reactor.error-report")
data class ErrorReportProperties(
    /** 오류 리포트 엔드포인트 활성화 여부. 기본값: false (명시적 opt-in 필요) */
    val enabled: Boolean = false,

    /** 수신 오류 리포트 인증용 API 키. 빈 문자열이면 인증 불필요. */
    val apiKey: String = "",

    /** 최대 동시 오류 리포트 처리 수 */
    val maxConcurrentRequests: Int = 3,

    /** 오류 분석 에이전트 실행 타임아웃 (밀리초). 기본값: 120초 */
    val requestTimeoutMs: Long = 120_000,

    /** 오류 분석 에이전트의 최대 도구 호출 수. 기본값: 25 */
    val maxToolCalls: Int = 25,

    /** 최대 스택 트레이스 길이 (문자 수). 초과 시 잘린다. */
    val maxStackTraceLength: Int = 30_000
)
