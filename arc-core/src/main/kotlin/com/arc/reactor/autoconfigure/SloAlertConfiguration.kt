package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.slo.DefaultSloAlertEvaluator
import com.arc.reactor.agent.slo.LoggingSloAlertNotifier
import com.arc.reactor.agent.slo.SloAlertEvaluator
import com.arc.reactor.agent.slo.SloAlertHook
import com.arc.reactor.agent.slo.SloAlertNotifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * SLO 알림 자동 설정.
 *
 * `arc.reactor.slo.enabled=true`일 때 활성화된다.
 * [SloAlertEvaluator], [SloAlertNotifier], [SloAlertHook] 빈을 등록한다.
 *
 * @see com.arc.reactor.agent.slo.DefaultSloAlertEvaluator 기본 평가기
 * @see com.arc.reactor.agent.slo.LoggingSloAlertNotifier 기본 로깅 알림기
 * @see com.arc.reactor.agent.slo.SloAlertHook AfterAgentComplete Hook
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.slo", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class SloAlertConfiguration {

    /** SLO 평가기: 슬라이딩 윈도우 기반 레이턴시/에러율 추적. */
    @Bean
    @ConditionalOnMissingBean
    fun sloAlertEvaluator(properties: AgentProperties): SloAlertEvaluator {
        val slo = properties.slo
        return DefaultSloAlertEvaluator(
            latencyThresholdMs = slo.latencyThresholdMs,
            errorRateThreshold = slo.errorRateThreshold,
            windowSeconds = slo.evaluationWindowSeconds,
            cooldownSeconds = slo.alertCooldownSeconds
        )
    }

    /** SLO 알림 발송기: 기본 로깅 구현체. */
    @Bean
    @ConditionalOnMissingBean
    fun sloAlertNotifier(): SloAlertNotifier = LoggingSloAlertNotifier()

    /** SLO 알림 Hook: 에이전트 완료 후 메트릭 기록 및 위반 평가. */
    @Bean
    @ConditionalOnMissingBean(name = ["sloAlertHook"])
    fun sloAlertHook(
        evaluator: SloAlertEvaluator,
        notifier: SloAlertNotifier
    ): SloAlertHook = SloAlertHook(evaluator, notifier)
}
