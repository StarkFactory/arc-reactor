package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.EvaluationMetricsHook
import com.arc.reactor.agent.metrics.MicrometerEvaluationMetricsCollector
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * 평가(Evaluation) 메트릭 자동 설정.
 *
 * R222 Directive #5 Benchmark-Aware Evaluation Loop 구현.
 *
 * ## 기본 동작 (opt-in 미활성)
 *
 * - [EvaluationMetricsCollector]: [NoOpEvaluationMetricsCollector] 주입 (오버헤드 0)
 * - [EvaluationMetricsHook]: 등록되지 않음
 *
 * ## 활성화 방법
 *
 * ### 옵션 A — Micrometer 백엔드 + Hook 등록
 *
 * `application.yml`에 다음 속성을 추가하면 Hook이 자동으로 등록되고, Micrometer
 * [MeterRegistry] 빈이 있으면 Micrometer 수집기가 사용된다:
 *
 * ```yaml
 * arc:
 *   reactor:
 *     evaluation:
 *       metrics:
 *         enabled: true
 * ```
 *
 * ### 옵션 B — 커스텀 수집기 직접 등록
 *
 * 사용자가 자체 [EvaluationMetricsCollector] 빈을 제공하면 자동 구성의 기본 no-op이
 * 대체된다. `arc.reactor.evaluation.metrics.enabled=true`를 함께 설정하면 Hook도
 * 자동 등록된다.
 *
 * ## 기존 메트릭과의 관계
 *
 * 이 자동 구성은 [SlaMetricsConfiguration]과 **독립적**이며 중복되지 않는다:
 *
 * - [SlaMetricsConfiguration] → SLO/SLA 보고 (E2E, 가용성, 수렴)
 * - [EvaluationMetricsConfiguration] → 평가셋 기반 개선 측정 (task success, tool calls,
 *   token cost, human override, safety rejection)
 */
@AutoConfiguration
@ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])
class EvaluationMetricsConfiguration {

    /**
     * Micrometer 기반 평가 메트릭 수집기 — [MeterRegistry]가 사용 가능할 때 기본.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EvaluationMetricsCollector::class)
    @ConditionalOnBean(MeterRegistry::class)
    fun micrometerEvaluationMetricsCollector(
        registry: MeterRegistry
    ): EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)

    /**
     * No-op 수집기 — Micrometer [MeterRegistry]가 없거나 사용자가 커스텀 빈을 제공하지
     * 않을 때의 기본값.
     */
    @Bean
    @ConditionalOnMissingBean
    fun noOpEvaluationMetricsCollector(): EvaluationMetricsCollector =
        NoOpEvaluationMetricsCollector

    /**
     * [EvaluationMetricsHook] 빈 — `arc.reactor.evaluation.metrics.enabled=true`일 때만
     * 등록되어 `AfterAgentCompleteHook` 체인에 참여한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "arc.reactor.evaluation.metrics",
        name = ["enabled"],
        havingValue = "true"
    )
    @ConditionalOnMissingBean(EvaluationMetricsHook::class)
    fun evaluationMetricsHook(
        collector: EvaluationMetricsCollector
    ): EvaluationMetricsHook = EvaluationMetricsHook(collector)
}
