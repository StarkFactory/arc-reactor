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
 * Output Guard Configuration (opt-in)
 *
 * Post-execution response validation: PII masking, pattern blocking.
 * `arc.reactor.output-guard.enabled=true`일 때만 활성화된다.
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
