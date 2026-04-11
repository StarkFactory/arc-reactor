package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.guard.ClassificationStage
import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.InjectionDetectionStage
import com.arc.reactor.guard.InputValidationStage
import com.arc.reactor.guard.RateLimitStage
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.audit.GuardAuditPublisher
import com.arc.reactor.guard.impl.CompositeClassificationStage
import com.arc.reactor.guard.impl.DefaultInjectionDetectionStage
import com.arc.reactor.guard.impl.DefaultInputValidationStage
import com.arc.reactor.guard.impl.DefaultRateLimitStage
import com.arc.reactor.guard.impl.GuardPipeline
import com.arc.reactor.guard.impl.LlmClassificationStage
import com.arc.reactor.guard.impl.RuleBasedClassificationStage
import com.arc.reactor.guard.impl.TopicDriftDetectionStage
import com.arc.reactor.guard.impl.UnicodeNormalizationStage
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Guard 설정
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.guard", name = ["enabled"],
    havingValue = "true", matchIfMissing = true
)
class GuardConfiguration {

    /**
     * Unicode 정규화 stage.
     *
     * R287 NOTE: marker interface 미존재 → name 기반 conditional 유지.
     * 향후 UnicodeNormalizationStage impl을 DefaultUnicodeNormalizationStage로 rename하고
     * `interface UnicodeNormalizationStage : GuardStage` 추가 시 type 기반으로 전환 가능.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["unicodeNormalizationStage"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.guard", name = ["unicode-normalization-enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun unicodeNormalizationStage(properties: AgentProperties): GuardStage =
        UnicodeNormalizationStage(maxZeroWidthRatio = properties.guard.maxZeroWidthRatio)

    /**
     * 속도 제한 stage.
     *
     * R287 fix: name 기반 → type 기반 [RateLimitStage] conditional. 사용자가 `@Bean fun
     * customRateLimit(): RateLimitStage = ...` 형태로 다른 이름의 bean을 등록해도 default가
     * 자동 비활성화되어 double-rate-limiting을 방지한다.
     */
    @Bean
    @ConditionalOnMissingBean(RateLimitStage::class)
    fun rateLimitStage(properties: AgentProperties): RateLimitStage =
        DefaultRateLimitStage(
            requestsPerMinute = properties.guard.rateLimitPerMinute,
            requestsPerHour = properties.guard.rateLimitPerHour,
            tenantRateLimits = properties.guard.tenantRateLimits
        )

    /**
     * 입력 검증 stage.
     *
     * R287 fix: name 기반 → type 기반 [InputValidationStage] conditional. 동일 사유.
     */
    @Bean
    @ConditionalOnMissingBean(InputValidationStage::class)
    fun inputValidationStage(properties: AgentProperties): InputValidationStage {
        return DefaultInputValidationStage(
            maxLength = properties.boundaries.inputMaxChars,
            minLength = properties.boundaries.inputMinChars,
            systemPromptMaxChars = properties.boundaries.systemPromptMaxChars
        )
    }

    /**
     * 인젝션 탐지 stage.
     *
     * R287 fix: name 기반 → type 기반 [InjectionDetectionStage] conditional. 동일 사유.
     */
    @Bean
    @ConditionalOnMissingBean(InjectionDetectionStage::class)
    @ConditionalOnProperty(
        prefix = "arc.reactor.guard", name = ["injection-detection-enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun injectionDetectionStage(): InjectionDetectionStage = DefaultInjectionDetectionStage()

    /**
     * 분류 stage.
     *
     * R287 fix: name 기반 → type 기반 [ClassificationStage] conditional. 동일 사유.
     * 반환 타입을 GuardStage → ClassificationStage로 좁혀 marker contract 명시.
     */
    @Bean
    @ConditionalOnMissingBean(ClassificationStage::class)
    @ConditionalOnProperty(
        prefix = "arc.reactor.guard", name = ["classification-enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun classificationStage(
        properties: AgentProperties,
        chatClient: ObjectProvider<ChatClient>
    ): ClassificationStage {
        val ruleBasedStage = RuleBasedClassificationStage()
        val llmStage = if (properties.guard.classificationLlmEnabled) {
            chatClient.ifAvailable?.let { LlmClassificationStage(it) }
        } else {
            null
        }
        return CompositeClassificationStage(ruleBasedStage, llmStage)
    }

    /**
     * 주제 이탈 탐지 stage.
     *
     * R287 NOTE: marker interface 미존재 → name 기반 conditional 유지.
     * 향후 TopicDriftDetectionStage impl을 DefaultTopicDriftDetectionStage로 rename하고
     * marker interface 추가 시 type 기반으로 전환 가능.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["topicDriftDetectionStage"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.guard", name = ["topic-drift-enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun topicDriftDetectionStage(
        memoryStore: ObjectProvider<MemoryStore>
    ): GuardStage = TopicDriftDetectionStage(memoryStore = memoryStore.ifAvailable)

    @Bean
    @ConditionalOnMissingBean
    fun requestGuard(
        stages: List<GuardStage>,
        auditPublisher: ObjectProvider<GuardAuditPublisher>,
        arcReactorTracerProvider: ObjectProvider<ArcReactorTracer>,
        evaluationMetricsCollectorProvider: ObjectProvider<EvaluationMetricsCollector>
    ): RequestGuard = GuardPipeline(
        stages = stages,
        auditPublisher = auditPublisher.ifAvailable,
        tracer = arcReactorTracerProvider.getIfAvailable { NoOpArcReactorTracer() },
        evaluationMetricsCollector = evaluationMetricsCollectorProvider.getIfAvailable {
            NoOpEvaluationMetricsCollector
        }
    )
}
