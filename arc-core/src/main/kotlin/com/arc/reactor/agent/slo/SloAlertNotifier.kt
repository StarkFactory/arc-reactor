package com.arc.reactor.agent.slo

/**
 * SLO 위반 알림 발송 인터페이스.
 *
 * 기본 구현체는 로깅으로 알림을 대체한다.
 * 사용자는 `@ConditionalOnMissingBean`을 통해 Slack, 이메일 등
 * 커스텀 알림 구현체를 등록할 수 있다.
 *
 * @see LoggingSloAlertNotifier 기본 로깅 구현체
 */
interface SloAlertNotifier {

    /**
     * SLO 위반 알림을 발송한다.
     *
     * @param violations 감지된 SLO 위반 목록
     */
    suspend fun notify(violations: List<SloViolation>)
}
