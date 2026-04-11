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
 * ## R264: 활성화 매트릭스 (R263 발견 동작 명시화)
 *
 * 이 자동 구성은 두 개의 독립된 빈을 등록하며, 각각 다른 조건으로 활성화된다.
 * **두 활성화 조건은 분리되어 있고, `arc.reactor.evaluation.metrics.enabled` 프로퍼티는
 * `EvaluationMetricsHook`만 제어한다 — `EvaluationMetricsCollector`는 `MeterRegistry`
 * 빈 존재만으로 Micrometer 구현체가 활성화된다**.
 *
 * 이는 R236 이후 줄곧 silent 동작이었으며, R263의 ApplicationContextRunner 통합
 * 테스트(`R263EvaluationMetricsContextIntegration` Test 2)가 이 동작을 명시적으로
 * 잠그고 R264가 KDoc로 문서화했다.
 *
 * | MeterRegistry 빈 | enabled 프로퍼티 | collector 결과 | Hook 등록 | DoctorDiagnostics 6번째 섹션 |
 * |------------------|-------------------|----------------|-----------|------------------------------|
 * | ❌ 없음           | ❌ 미설정          | NoOp           | ❌         | SKIPPED |
 * | ❌ 없음           | ✅ true            | NoOp           | ✅ (NoOp 래핑) | SKIPPED |
 * | ✅ 있음           | ❌ 미설정          | **Micrometer** | ❌         | **OK** |
 * | ✅ 있음           | ✅ true            | **Micrometer** | ✅         | **OK** |
 *
 * ### 핵심 관찰
 *
 * - **`MeterRegistry`가 컨텍스트에 등록되어 있고 `enabled` 프로퍼티가 없는 환경**에서도
 *   collector는 활성 상태로 간주된다. 이는 운영자가 직접 `collector.recordXxx()`를
 *   호출하여 메트릭을 기록할 의사가 있다고 보는 자유로운 모델이다.
 * - **Hook 자동 등록이 필요하면 `enabled=true`를 명시**해야 한다. Hook 없이는
 *   `AfterAgentCompleteHook` 체인이 자동으로 메트릭을 기록하지 않는다.
 * - **사용자 커스텀 collector 빈**을 등록하면 `@ConditionalOnMissingBean`으로 인해
 *   Micrometer/NoOp 빈 정의가 모두 평가되지 않는다. 이 경우 collector는 사용자
 *   구현체이며, `enabled=true`를 함께 설정하지 않으면 Hook은 등록되지 않는다.
 *
 * ## 기본 동작 (opt-in 미활성)
 *
 * - [EvaluationMetricsCollector]: [MeterRegistry] 빈이 없으면 [NoOpEvaluationMetricsCollector] 주입 (오버헤드 0)
 * - [EvaluationMetricsCollector]: [MeterRegistry] 빈이 있으면 [MicrometerEvaluationMetricsCollector] 주입 (enabled 프로퍼티 무관)
 * - [EvaluationMetricsHook]: 등록되지 않음 (`enabled=true` 설정 시에만 등록)
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
 * ### 옵션 C (R264 명시) — MeterRegistry만 등록 (Hook 없이 직접 호출)
 *
 * Spring Boot Actuator나 사용자 코드로 [MeterRegistry] 빈만 등록하고 `enabled`
 * 프로퍼티를 설정하지 않으면, [MicrometerEvaluationMetricsCollector]가 자동으로
 * 등록되지만 [EvaluationMetricsHook]은 등록되지 않는다. 이 시나리오는 운영자가
 * 직접 collector 메서드를 호출하여 메트릭을 기록하고 싶을 때 유용하다.
 *
 * ## 기존 메트릭과의 관계
 *
 * 이 자동 구성은 [SlaMetricsConfiguration]과 **독립적**이며 중복되지 않는다:
 *
 * - [SlaMetricsConfiguration] → SLO/SLA 보고 (E2E, 가용성, 수렴)
 * - [EvaluationMetricsConfiguration] → 평가셋 기반 개선 측정 (task success, tool calls,
 *   token cost, human override, safety rejection)
 *
 * ## 변경 시 주의 (R264 잠금 사항)
 *
 * `micrometerEvaluationMetricsCollector` 빈에 `@ConditionalOnProperty(... enabled=true)`를
 * 추가하면 위 활성화 매트릭스의 3행이 깨진다. R263 통합 테스트(`R263EvaluationMetricsContextIntegration`
 * Test 2)가 이 변경을 즉시 차단하므로, 만약 의도된 동작 변경이라면 해당 테스트도 함께 갱신해야 한다.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])
class EvaluationMetricsConfiguration {

    /**
     * Micrometer 기반 평가 메트릭 수집기 — [MeterRegistry]가 사용 가능할 때 기본.
     *
     * R264 잠금: 이 빈은 **`arc.reactor.evaluation.metrics.enabled` 프로퍼티와 무관**하다.
     * `MeterRegistry` 빈이 컨텍스트에 등록되어 있기만 하면 Micrometer 구현체가 활성화된다.
     * 활성화 조건을 enabled 프로퍼티에 묶고 싶다면 [evaluationMetricsHook] 빈처럼
     * `@ConditionalOnProperty`를 추가하되, 이는 행동 변경이므로 R263 통합 테스트
     * (`R263EvaluationMetricsContextIntegration` Test 2)도 함께 업데이트해야 한다.
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
     * 않을 때의 기본값. `@ConditionalOnMissingBean` 덕분에 사용자 커스텀 collector나
     * Micrometer 변형이 등록되면 평가되지 않는다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun noOpEvaluationMetricsCollector(): EvaluationMetricsCollector =
        NoOpEvaluationMetricsCollector

    /**
     * [EvaluationMetricsHook] 빈 — `arc.reactor.evaluation.metrics.enabled=true`일 때만
     * 등록되어 `AfterAgentCompleteHook` 체인에 참여한다.
     *
     * R264 명시: 이 빈만 enabled 프로퍼티에 묶여 있다. collector 자체는 [MeterRegistry]
     * 등록만으로 활성화되므로, Hook 없이 collector 메서드를 직접 호출하는 운영 시나리오도
     * 가능하다 (옵션 C).
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
