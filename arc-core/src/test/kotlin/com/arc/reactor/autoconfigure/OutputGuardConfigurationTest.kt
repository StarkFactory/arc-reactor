package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.OutputGuardProperties
import com.arc.reactor.guard.output.impl.OutputBlockPattern
import com.arc.reactor.guard.output.impl.PatternAction
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.impl.DynamicRuleOutputGuard
import com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard
import com.arc.reactor.guard.output.impl.RegexPatternOutputGuard
import com.arc.reactor.guard.output.policy.OutputGuardRuleEvaluator
import com.arc.reactor.guard.output.policy.OutputGuardRuleInvalidationBus
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * R269: [OutputGuardConfiguration] R269 KDoc 활성화 매트릭스 검증.
 *
 * R267/R268 패턴(Summarizer)을 더 복잡한 케이스(4개 빈, 2개의 opt-out matchIfMissing,
 * 1개의 nullable bean, 빈 pipeline silent ineffective)에 적용한다.
 *
 * R263 → R267 → R269 doc-test 패턴의 네 번째 적용 (R263/R266 단순 → R267/R268 결합 →
 * R269 더 복잡한 4-bean Configuration).
 *
 * ## 검증 매트릭스 매핑
 *
 * R269 KDoc 매트릭스 ↔ 본 테스트의 nested 클래스:
 *
 * | KDoc 시나리오 | nested 클래스 |
 * |---|---|
 * | 클래스 활성화 매트릭스 | [ClassActivation] |
 * | piiMaskingOutputGuard 결정 트리 | [PiiMaskingActivation] |
 * | regexPatternOutputGuard 결정 트리 (nullable bean) | [RegexPatternActivation] |
 * | dynamicRuleOutputGuard 결정 트리 | [DynamicRuleActivation] |
 * | 빈 pipeline silent ineffective | [EmptyPipelineSilentIneffective] |
 */
class OutputGuardConfigurationTest {

    /** 테스트용 AgentProperties — 기본값 (출력 가드 미활성). */
    private fun defaultAgentProperties(): AgentProperties = AgentProperties()

    /** 테스트용 AgentProperties — customPatterns 포함. */
    private fun agentPropertiesWithPatterns(): AgentProperties = AgentProperties().copy(
        outputGuard = OutputGuardProperties(
            customPatterns = listOf(
                OutputBlockPattern(
                    name = "test pattern",
                    pattern = "secret-token-\\d+",
                    action = PatternAction.REJECT
                )
            )
        )
    )

    /** OutputGuard 의존성을 mock으로 등록한 베이스 contextRunner. */
    private val baseContextRunner = ApplicationContextRunner()
        .withUserConfiguration(OutputGuardConfiguration::class.java)
        .withBean(AgentProperties::class.java, ::defaultAgentProperties)
        .withBean(AgentMetrics::class.java, { mockk<AgentMetrics>(relaxed = true) })
        .withBean(OutputGuardRuleStore::class.java, { mockk<OutputGuardRuleStore>(relaxed = true) })
        .withBean(OutputGuardRuleInvalidationBus::class.java, { OutputGuardRuleInvalidationBus() })
        .withBean(OutputGuardRuleEvaluator::class.java, { OutputGuardRuleEvaluator() })

    @Nested
    inner class ClassActivation {

        @Test
        fun `R269 enabled 미설정 시 클래스 평가 안 됨 - 모든 빈 미등록`() {
            baseContextRunner.run { context ->
                assertTrue(
                    context.getBeansOfType(OutputGuardPipeline::class.java).isEmpty()
                ) { "enabled 미설정 → outputGuardPipeline 미등록" }
                assertTrue(
                    context.getBeansOfType(OutputGuardStage::class.java).isEmpty()
                ) { "enabled 미설정 → 모든 stage 미등록" }
            }
        }

        @Test
        fun `R269 enabled false 명시 시 클래스 평가 안 됨`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.output-guard.enabled=false")
                .run { context ->
                    assertTrue(
                        context.getBeansOfType(OutputGuardPipeline::class.java).isEmpty()
                    ) { "enabled=false → 클래스 스킵" }
                }
        }

        @Test
        fun `R269 enabled true + 기본 설정 - PII 마스킹과 동적 규칙은 자동 활성, regex는 미등록`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.output-guard.enabled=true")
                .run { context ->
                    val stages = context.getBeansOfType(OutputGuardStage::class.java)

                    // PII 마스킹은 matchIfMissing=true → 자동 활성
                    assertTrue(
                        stages.values.any { it is PiiMaskingOutputGuard }
                    ) { "R269 silent #1: PII 마스킹은 opt-out 모델로 자동 활성" }

                    // 동적 규칙도 matchIfMissing=true → 자동 활성
                    assertTrue(
                        stages.values.any { it is DynamicRuleOutputGuard }
                    ) { "R269 silent #2: 동적 규칙도 opt-out 모델로 자동 활성" }

                    // regex 패턴은 customPatterns 비어있으므로 nullable bean 반환
                    assertFalse(
                        stages.values.any { it is RegexPatternOutputGuard }
                    ) { "R269 silent #3: 패턴 없으면 regex bean 미등록 (nullable bean)" }

                    // pipeline은 stages를 받아 정상 등록
                    assertTrue(
                        context.getBeansOfType(OutputGuardPipeline::class.java).isNotEmpty()
                    ) { "outputGuardPipeline 등록" }
                }
        }
    }

    @Nested
    inner class PiiMaskingActivation {

        @Test
        fun `R269 silent 1 - pii-masking-enabled 미설정 시 PiiMaskingOutputGuard 자동 활성 (opt-out)`() {
            // 운영자가 pii-masking-enabled를 명시하지 않아도 PII 마스킹이 자동 활성화됨
            // (matchIfMissing = true로 인한 default-secure 동작)
            baseContextRunner
                .withPropertyValues("arc.reactor.output-guard.enabled=true")
                .run { context ->
                    val stages = context.getBeansOfType(OutputGuardStage::class.java)
                    assertTrue(
                        stages.values.any { it is PiiMaskingOutputGuard }
                    ) {
                        "R269 silent #1 잠금: pii-masking-enabled 미설정 시 자동 활성 " +
                            "(opt-out 모델, R267/R268 Summarizer의 opt-in과 정반대)"
                    }
                }
        }

        @Test
        fun `R269 pii-masking-enabled false 명시 시 PiiMaskingOutputGuard 미등록`() {
            baseContextRunner
                .withPropertyValues(
                    "arc.reactor.output-guard.enabled=true",
                    "arc.reactor.output-guard.pii-masking-enabled=false"
                )
                .run { context ->
                    val stages = context.getBeansOfType(OutputGuardStage::class.java)
                    assertFalse(
                        stages.values.any { it is PiiMaskingOutputGuard }
                    ) { "pii-masking-enabled=false → 명시적으로 비활성화" }
                }
        }
    }

    @Nested
    inner class RegexPatternActivation {

        @Test
        fun `R269 silent 3 - customPatterns 비어있으면 nullable bean으로 미등록`() {
            // regexPatternOutputGuard는 OutputGuardStage? 반환
            // 패턴이 없으면 null → Spring은 빈을 등록하지 않음
            baseContextRunner
                .withPropertyValues("arc.reactor.output-guard.enabled=true")
                .run { context ->
                    val stages = context.getBeansOfType(OutputGuardStage::class.java)
                    assertFalse(
                        stages.values.any { it is RegexPatternOutputGuard }
                    ) {
                        "R269 silent #3 잠금: customPatterns 비어있음 → nullable bean → 미등록"
                    }
                }
        }

        @Test
        fun `R269 customPatterns 제공 시 RegexPatternOutputGuard 등록`() {
            ApplicationContextRunner()
                .withUserConfiguration(OutputGuardConfiguration::class.java)
                .withBean(AgentProperties::class.java, ::agentPropertiesWithPatterns)
                .withBean(AgentMetrics::class.java, { mockk<AgentMetrics>(relaxed = true) })
                .withBean(OutputGuardRuleStore::class.java, { mockk<OutputGuardRuleStore>(relaxed = true) })
                .withBean(OutputGuardRuleInvalidationBus::class.java, { OutputGuardRuleInvalidationBus() })
                .withBean(OutputGuardRuleEvaluator::class.java, { OutputGuardRuleEvaluator() })
                .withPropertyValues("arc.reactor.output-guard.enabled=true")
                .run { context ->
                    val stages = context.getBeansOfType(OutputGuardStage::class.java)
                    assertTrue(
                        stages.values.any { it is RegexPatternOutputGuard }
                    ) {
                        "customPatterns 제공 → regex bean 등록"
                    }
                }
        }
    }

    @Nested
    inner class DynamicRuleActivation {

        @Test
        fun `R269 silent 2 - dynamic-rules-enabled 미설정 시 DynamicRuleOutputGuard 자동 활성 (opt-out)`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.output-guard.enabled=true")
                .run { context ->
                    val stages = context.getBeansOfType(OutputGuardStage::class.java)
                    assertTrue(
                        stages.values.any { it is DynamicRuleOutputGuard }
                    ) {
                        "R269 silent #2 잠금: dynamic-rules-enabled 미설정 시 자동 활성 " +
                            "(opt-out 모델)"
                    }
                }
        }

        @Test
        fun `R269 dynamic-rules-enabled false 명시 시 DynamicRuleOutputGuard 미등록`() {
            baseContextRunner
                .withPropertyValues(
                    "arc.reactor.output-guard.enabled=true",
                    "arc.reactor.output-guard.dynamic-rules-enabled=false"
                )
                .run { context ->
                    val stages = context.getBeansOfType(OutputGuardStage::class.java)
                    assertFalse(
                        stages.values.any { it is DynamicRuleOutputGuard }
                    ) { "dynamic-rules-enabled=false → 명시적으로 비활성화" }
                }
        }
    }

    @Nested
    inner class EmptyPipelineSilentIneffective {

        @Test
        fun `R269 silent 4 - 모든 stage 비활성 + 패턴 없음 시 빈 pipeline로 사실상 검사 없음`() {
            // 운영자가 output-guard.enabled=true를 의도했으나
            // 모든 개별 stage를 비활성화한 silent 위험 시나리오
            baseContextRunner
                .withPropertyValues(
                    "arc.reactor.output-guard.enabled=true",
                    "arc.reactor.output-guard.pii-masking-enabled=false",
                    "arc.reactor.output-guard.dynamic-rules-enabled=false"
                )
                .run { context ->
                    val stages = context.getBeansOfType(OutputGuardStage::class.java)
                    assertEquals(0, stages.size) {
                        "R269 silent #4 잠금: 모든 stage 비활성 + 패턴 없음 → 0개 stage. " +
                            "actual=${stages.keys}"
                    }

                    // pipeline은 등록되지만 빈 stages로 동작
                    val pipelines = context.getBeansOfType(OutputGuardPipeline::class.java)
                    assertEquals(1, pipelines.size) {
                        "pipeline은 항상 등록 — 운영자는 활성화한 줄로 알지만 " +
                            "사실상 어떤 검사도 수행하지 않음"
                    }
                }
        }

        @Test
        fun `R269 운영자 의도와 빈 pipeline silent 갭 명시`() {
            // 매우 위험한 silent 동작: 운영자가 PII 마스킹과 동적 규칙을 명시적으로 끄고
            // 패턴도 제공하지 않으면 output-guard가 사실상 동작하지 않는다
            baseContextRunner
                .withPropertyValues(
                    "arc.reactor.output-guard.enabled=true", // 운영자 의도: 활성화
                    "arc.reactor.output-guard.pii-masking-enabled=false",
                    "arc.reactor.output-guard.dynamic-rules-enabled=false"
                )
                .run { context ->
                    val pipelines = context.getBeansOfType(OutputGuardPipeline::class.java)
                    assertEquals(1, pipelines.size)

                    val stages = context.getBeansOfType(OutputGuardStage::class.java)
                    assertEquals(0, stages.size) {
                        "운영자 의도(enabled=true)와 실제 결과(0 stages)의 갭 — " +
                            "사실상 ineffective"
                    }
                }
        }
    }

    @Nested
    inner class FullActivation {

        @Test
        fun `R269 모든 기능 활성 - PII + 동적 규칙 + customPatterns 모두 등록`() {
            ApplicationContextRunner()
                .withUserConfiguration(OutputGuardConfiguration::class.java)
                .withBean(AgentProperties::class.java, ::agentPropertiesWithPatterns)
                .withBean(AgentMetrics::class.java, { mockk<AgentMetrics>(relaxed = true) })
                .withBean(OutputGuardRuleStore::class.java, { mockk<OutputGuardRuleStore>(relaxed = true) })
                .withBean(OutputGuardRuleInvalidationBus::class.java, { OutputGuardRuleInvalidationBus() })
                .withBean(OutputGuardRuleEvaluator::class.java, { OutputGuardRuleEvaluator() })
                .withPropertyValues("arc.reactor.output-guard.enabled=true")
                .run { context ->
                    val stages = context.getBeansOfType(OutputGuardStage::class.java)
                    assertEquals(3, stages.size) {
                        "PII + Dynamic + Regex = 3 stages. actual=${stages.keys}"
                    }
                    assertTrue(stages.values.any { it is PiiMaskingOutputGuard })
                    assertTrue(stages.values.any { it is DynamicRuleOutputGuard })
                    assertTrue(stages.values.any { it is RegexPatternOutputGuard })
                }
        }
    }
}
