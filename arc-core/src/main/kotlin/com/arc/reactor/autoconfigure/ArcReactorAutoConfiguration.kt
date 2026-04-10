package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.promptlab.autoconfigure.PromptLabConfiguration
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import

/**
 * Arc Reactor 자동 설정
 *
 * Arc Reactor 컴포넌트를 위한 Spring Boot 자동 설정 진입점.
 * ChatModel 빈과 JDBC 인프라가 사용 가능하도록 관련 자동 설정 이후에 실행된다.
 */
@AutoConfiguration(
    after = [
        GoogleGenAiChatAutoConfiguration::class,
        ChatClientAutoConfiguration::class,
        DataSourceAutoConfiguration::class,
        JdbcTemplateAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class,
        TransactionAutoConfiguration::class
    ]
)
@EnableConfigurationProperties(AgentProperties::class)
@Import(
    ReactorContextPropagationConfiguration::class,
    TracingConfiguration::class,
    ArcReactorCoreBeansConfiguration::class,
    ArcReactorPreflightConfiguration::class,
    ArcReactorHookAndMcpConfiguration::class,
    ArcReactorSemanticCacheConfiguration::class,
    ArcReactorRuntimeConfiguration::class,
    ArcReactorTokenRevocationStoreConfiguration::class,
    ArcReactorExecutorConfiguration::class,
    JdbcMemoryStoreConfiguration::class,
    JdbcToolPolicyStoreConfiguration::class,
    JdbcRagIngestionPolicyStoreConfiguration::class,
    JdbcMcpSecurityPolicyStoreConfiguration::class,
    GuardConfiguration::class,
    OutputGuardConfiguration::class,
    RagConfiguration::class,
    AuthConfiguration::class,
    JdbcAuthConfiguration::class,
    IntentConfiguration::class,
    JdbcIntentRegistryConfiguration::class,
    SchedulerConfiguration::class,
    MemorySummaryConfiguration::class,
    JdbcConversationSummaryStoreConfiguration::class,
    UserMemoryConfiguration::class,
    JdbcUserMemoryStoreConfiguration::class,
    PromptLabConfiguration::class,
    PromptCachingConfiguration::class,
    HealthIndicatorConfiguration::class,
    CanaryConfiguration::class,
    ToolSanitizerConfiguration::class,
    ToolIdempotencyConfiguration::class,
    CheckpointConfiguration::class,
    ModelRoutingConfiguration::class,
    ModeResolverConfiguration::class,
    MultiAgentConfiguration::class,
    BudgetConfiguration::class,
    ToolDescriptionEnrichmentConfiguration::class,
    SlaMetricsConfiguration::class,
    EvaluationMetricsConfiguration::class,
    ToolResponseSummarizerConfiguration::class,
    ToolDependencyConfiguration::class,
    SloAlertConfiguration::class,
    CostAnomalyConfiguration::class,
    PromptDriftConfiguration::class,
    GuardBlockRateConfiguration::class,
    JiraAccountIdResolverConfiguration::class
)
class ArcReactorAutoConfiguration
