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
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Prompt Lab auto-configuration.
 *
 * Registers all Prompt Lab beans when `arc.reactor.prompt-lab.enabled=true`.
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
