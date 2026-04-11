package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.impl.DynamicRuleOutputGuard
import com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard
import com.arc.reactor.guard.output.impl.RegexPatternOutputGuard
import com.arc.reactor.guard.output.policy.OutputGuardRuleEvaluator
import com.arc.reactor.guard.output.policy.OutputGuardRuleInvalidationBus
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Output Guard Configuration (opt-in).
 *
 * Post-execution 응답 검증: PII 마스킹, 패턴 차단, 동적 규칙 평가.
 * `arc.reactor.output-guard.enabled=true`일 때만 활성화된다.
 *
 * ## R269: 활성화 매트릭스 (R264~R268 패턴 확장)
 *
 * 이 자동 구성은 4개의 빈을 등록하며, 그 중 3개는 stage 빈이고 1개는 pipeline이다.
 * **`output-guard.enabled=true` 한 줄로 활성화하면 PII 마스킹과 동적 규칙은 자동 활성화**
 * (opt-out 모델, `matchIfMissing=true`)되지만 regex 패턴은 명시적으로 패턴을 제공해야 한다.
 *
 * ### 클래스 활성화 매트릭스
 *
 * | `output-guard.enabled` | 결과 |
 * |---|---|
 * | ❌ false (또는 미설정) | 클래스 평가 안 됨 → 모든 빈 미등록 |
 * | ✅ true | 클래스 평가 → 빈 등록 결정 트리 (다음 표) |
 *
 * ### 빈 등록 결정 트리 (`output-guard.enabled=true` 가정)
 *
 * | 빈 | 추가 조건 | 활성화 기본값 |
 * |---|---|---|
 * | `piiMaskingOutputGuard` | `pii-masking-enabled` (default true) | **자동 활성** (opt-out) |
 * | `regexPatternOutputGuard` | `outputGuard.customPatterns` 비어있지 않음 | **opt-in** (패턴 명시 필요) |
 * | `dynamicRuleOutputGuard` | `dynamic-rules-enabled` (default true) | **자동 활성** (opt-out) |
 * | `outputGuardPipeline` | 항상 (필수 의존성 충족 시) | 항상 등록 |
 *
 * ### ⚠️ R269 발견 silent 동작 #1 — Opt-out PII 마스킹
 *
 * `piiMaskingOutputGuard`는 `@ConditionalOnProperty(name = ["pii-masking-enabled"], havingValue = "true",
 * matchIfMissing = true)`이다. **즉 운영자가 `pii-masking-enabled` 프로퍼티를 명시하지 않으면
 * 자동으로 활성화**된다 — 이는 R232 PII Redaction이나 R231 Tool Response Summarizer의 opt-in
 * 패턴과 정반대다. 보안상 안전한 기본값(default-secure)이지만 운영자 의도와 다를 수 있다.
 *
 * 운영자가 PII 마스킹을 끄려면 명시적으로 `pii-masking-enabled=false`를 설정해야 한다.
 *
 * ### ⚠️ R269 발견 silent 동작 #2 — Opt-out 동적 규칙
 *
 * `dynamicRuleOutputGuard`도 동일한 패턴으로 `dynamic-rules-enabled` 미설정 시 자동 활성화된다.
 * 단, `dynamicRuleOutputGuard`는 추가로 `OutputGuardRuleStore`/`OutputGuardRuleInvalidationBus`/
 * `OutputGuardRuleEvaluator` 빈이 컨텍스트에 있어야 동작한다. 이 빈들이 없으면 컨텍스트 기동이
 * 실패할 수 있다.
 *
 * ### ⚠️ R269 발견 silent 동작 #3 — Nullable 빈 반환
 *
 * `regexPatternOutputGuard`는 `OutputGuardStage?` 타입을 반환한다. 패턴 리스트가 비어있으면
 * `null`을 반환하는데, Spring은 `@Bean` 메서드가 `null`을 반환하면 해당 빈을 등록하지 않는다.
 * 운영자가 `enabled=true`만 설정하고 패턴을 제공하지 않으면 이 빈은 조용히 누락된다.
 *
 * ### ⚠️ R269 발견 silent 동작 #4 — 빈 pipeline 효과
 *
 * 운영자가 `output-guard.enabled=true`를 설정하고 다른 모든 stage를 비활성화한 경우
 * (`pii-masking-enabled=false`, 패턴 없음, `dynamic-rules-enabled=false`), `outputGuardPipeline`
 * 은 빈 stages 리스트로 구성된다. 이 경우 pipeline은 호출되지만 어떤 검사도 수행하지 않으며,
 * 운영자는 "Output Guard 활성화"라는 의도와 달리 **사실상 검사가 동작하지 않는다**.
 *
 * 이 silent 동작을 방지하려면 운영자가 `pii-masking-enabled` 또는 `dynamic-rules-enabled`를
 * 명시적으로 활성화 상태로 두거나 `customPatterns`를 제공해야 한다.
 *
 * ### 변경 시 주의 (잠금 사항)
 *
 * 1. `pii-masking-enabled`의 `matchIfMissing` 변경 시 default-secure 정책 깨짐 — KDoc + R269 테스트 갱신 필수
 * 2. `regexPatternOutputGuard`의 nullable 반환 → non-nullable로 변경 시 빈 패턴 케이스 처리 변경
 * 3. `outputGuardPipeline`의 stages 리스트가 비어있을 때 fail-fast 추가 시 backward compat 영향 검토
 * 4. `dynamicRuleOutputGuard`의 의존성(Store/Bus/Evaluator) 변경 시 컨텍스트 기동 영향
 *
 * ## 3대 최상위 제약 준수
 *
 * - MCP: 도구 응답 경로 미수정 (Output Guard는 LLM 응답에만 적용)
 * - Redis 캐시: `systemPrompt` 미수정 → scopeFingerprint 불변
 * - 컨텍스트 관리: `MemoryStore`/`Trimmer` 미수정
 *
 * @see EvaluationMetricsConfiguration R264 활성화 매트릭스 (자매 패턴)
 * @see ArcReactorSemanticCacheConfiguration R265 활성화 매트릭스 (자매 패턴)
 * @see ToolResponseSummarizerConfiguration R267/R268 활성화 매트릭스 + production fix (자매 패턴)
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.output-guard", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class OutputGuardConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["piiMaskingOutputGuard"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.output-guard", name = ["pii-masking-enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun piiMaskingOutputGuard(): OutputGuardStage = PiiMaskingOutputGuard()

    @Bean
    @ConditionalOnMissingBean(name = ["regexPatternOutputGuard"])
    fun regexPatternOutputGuard(properties: AgentProperties): OutputGuardStage? {
        val patterns = properties.outputGuard.customPatterns
        if (patterns.isEmpty()) return null
        return RegexPatternOutputGuard(patterns)
    }

    @Bean
    @ConditionalOnMissingBean(name = ["dynamicRuleOutputGuard"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.output-guard", name = ["dynamic-rules-enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun dynamicRuleOutputGuard(
        properties: AgentProperties,
        outputGuardRuleStore: OutputGuardRuleStore,
        invalidationBus: OutputGuardRuleInvalidationBus,
        evaluator: OutputGuardRuleEvaluator
    ): OutputGuardStage = DynamicRuleOutputGuard(
        store = outputGuardRuleStore,
        refreshIntervalMs = properties.outputGuard.dynamicRulesRefreshMs,
        invalidationBus = invalidationBus,
        evaluator = evaluator
    )

    @Bean
    @ConditionalOnMissingBean
    fun outputGuardPipeline(
        stages: List<OutputGuardStage>,
        agentMetrics: AgentMetrics,
        arcReactorTracerProvider: ObjectProvider<ArcReactorTracer>,
        evaluationMetricsCollectorProvider: ObjectProvider<EvaluationMetricsCollector>
    ): OutputGuardPipeline =
        OutputGuardPipeline(
            stages = stages,
            onStageComplete = { stage, action, reason ->
                agentMetrics.recordOutputGuardAction(stage, action, reason)
            },
            tracer = arcReactorTracerProvider.getIfAvailable { NoOpArcReactorTracer() },
            evaluationMetricsCollector = evaluationMetricsCollectorProvider.getIfAvailable {
                NoOpEvaluationMetricsCollector
            }
        )
}
