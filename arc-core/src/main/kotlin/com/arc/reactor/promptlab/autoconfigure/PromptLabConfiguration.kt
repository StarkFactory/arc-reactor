package com.arc.reactor.promptlab.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.promptlab.ExperimentOrchestrator
import com.arc.reactor.promptlab.ExperimentStore
import com.arc.reactor.promptlab.InMemoryExperimentStore
import com.arc.reactor.promptlab.PromptLabProperties
import com.arc.reactor.promptlab.PromptLabScheduler
import com.arc.reactor.promptlab.ReportGenerator
import com.arc.reactor.promptlab.analysis.FeedbackAnalyzer
import com.arc.reactor.promptlab.analysis.PromptCandidateGenerator
import com.arc.reactor.promptlab.eval.EvaluationPipelineFactory
import com.arc.reactor.promptlab.eval.LlmJudgeEvaluator
import com.arc.reactor.promptlab.eval.RuleBasedEvaluator
import com.arc.reactor.promptlab.eval.StructuralEvaluator
import com.arc.reactor.promptlab.hook.ExperimentCaptureHook
import com.arc.reactor.promptlab.hook.LiveExperimentResultRecorder
import com.arc.reactor.promptlab.InMemoryLiveExperimentStore
import com.arc.reactor.promptlab.LiveExperimentStore
import com.arc.reactor.promptlab.PromptExperimentRouter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Prompt Lab 자동 설정.
 *
 * `arc.reactor.prompt-lab.enabled=true`일 때 모든 Prompt Lab 빈을 등록한다.
 *
 * WHY: Prompt Lab은 선택적 기능이므로 설정으로 활성화해야만 빈이 등록된다.
 * 비활성화 시 불필요한 빈 생성과 LLM 호출 비용을 방지한다.
 * 모든 빈에 @ConditionalOnMissingBean을 적용하여 사용자 커스텀 구현으로 교체 가능하다.
 *
 * @see PromptLabProperties 설정 프로퍼티
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.prompt-lab",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class PromptLabConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun promptLabProperties(properties: AgentProperties): PromptLabProperties =
        properties.promptLab

    @Bean
    @ConditionalOnMissingBean
    fun experimentStore(): ExperimentStore = InMemoryExperimentStore()

    @Bean
    @ConditionalOnMissingBean
    fun structuralEvaluator(): StructuralEvaluator = StructuralEvaluator()

    @Bean
    @ConditionalOnMissingBean
    fun ruleBasedEvaluator(): RuleBasedEvaluator = RuleBasedEvaluator()

    @Bean
    @ConditionalOnMissingBean
    fun llmJudgeEvaluator(
        chatModelProvider: ChatModelProvider,
        properties: AgentProperties
    ): LlmJudgeEvaluator {
        val labProps = properties.promptLab
        return LlmJudgeEvaluator(
            chatModelProvider = chatModelProvider,
            judgeModel = labProps.defaultJudgeModel
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun evaluationPipelineFactory(
        structural: StructuralEvaluator,
        rules: RuleBasedEvaluator,
        llmJudge: ObjectProvider<LlmJudgeEvaluator>
    ): EvaluationPipelineFactory {
        return EvaluationPipelineFactory(structural, rules, llmJudge.ifAvailable)
    }

    @Bean
    @ConditionalOnMissingBean
    fun feedbackAnalyzer(
        feedbackStore: FeedbackStore,
        chatModelProvider: ChatModelProvider
    ): FeedbackAnalyzer = FeedbackAnalyzer(feedbackStore, chatModelProvider)

    @Bean
    @ConditionalOnMissingBean
    fun promptCandidateGenerator(
        chatModelProvider: ChatModelProvider,
        promptTemplateStore: PromptTemplateStore
    ): PromptCandidateGenerator {
        return PromptCandidateGenerator(chatModelProvider, promptTemplateStore)
    }

    @Bean
    @ConditionalOnMissingBean
    fun reportGenerator(): ReportGenerator = ReportGenerator()

    @Bean
    @ConditionalOnMissingBean
    fun experimentOrchestrator(
        agentExecutor: AgentExecutor,
        promptTemplateStore: PromptTemplateStore,
        experimentStore: ExperimentStore,
        evaluationPipelineFactory: EvaluationPipelineFactory,
        reportGenerator: ReportGenerator,
        feedbackAnalyzer: FeedbackAnalyzer,
        candidateGenerator: PromptCandidateGenerator,
        properties: AgentProperties
    ): ExperimentOrchestrator {
        return ExperimentOrchestrator(
            agentExecutor = agentExecutor,
            promptTemplateStore = promptTemplateStore,
            experimentStore = experimentStore,
            evaluationPipelineFactory = evaluationPipelineFactory,
            reportGenerator = reportGenerator,
            feedbackAnalyzer = feedbackAnalyzer,
            candidateGenerator = candidateGenerator,
            properties = properties.promptLab
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun experimentCaptureHook(): ExperimentCaptureHook = ExperimentCaptureHook()

    // ── 라이브 A/B 테스트 ──

    @Configuration
    @ConditionalOnProperty(
        prefix = "arc.reactor.prompt-lab.live-experiment",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false
    )
    class LiveExperimentConfiguration {

        @Bean
        @ConditionalOnMissingBean
        fun liveExperimentStore(
            properties: AgentProperties
        ): LiveExperimentStore {
            val liveProps = properties.promptLab.liveExperiment
            return InMemoryLiveExperimentStore(
                maxResultsPerExperiment = liveProps.maxResultsPerExperiment
            )
        }

        @Bean
        @ConditionalOnMissingBean
        fun promptExperimentRouter(
            liveExperimentStore: LiveExperimentStore
        ): PromptExperimentRouter = PromptExperimentRouter(liveExperimentStore)

        @Bean
        @ConditionalOnMissingBean
        fun liveExperimentResultRecorder(
            liveExperimentStore: LiveExperimentStore
        ): LiveExperimentResultRecorder {
            return LiveExperimentResultRecorder(liveExperimentStore)
        }
    }

    @Configuration
    @ConditionalOnProperty(
        prefix = "arc.reactor.prompt-lab.schedule",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false
    )
    @EnableScheduling
    class SchedulerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        fun promptLabScheduler(
            orchestrator: ExperimentOrchestrator,
            promptTemplateStore: PromptTemplateStore,
            properties: AgentProperties
        ): PromptLabScheduler {
            return PromptLabScheduler(orchestrator, promptTemplateStore, properties.promptLab)
        }
    }
}
