package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.promptlab.autoconfigure.PromptLabConfiguration
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import

/**
 * Arc Reactor 자동 설정
 *
 * Arc Reactor 컴포넌트를 위한 Spring Boot 자동 설정 진입점.
 * ChatModel 빈이 사용 가능하도록 Spring AI 프로바이더 자동 설정 이후에 실행된다.
 */
@AutoConfiguration(after = [GoogleGenAiChatAutoConfiguration::class, ChatClientAutoConfiguration::class])
@EnableConfigurationProperties(AgentProperties::class)
@Import(
    TracingConfiguration::class,
    ArcReactorCoreBeansConfiguration::class,
    ArcReactorHookAndMcpConfiguration::class,
    ArcReactorRuntimeConfiguration::class,
    ArcReactorSemanticCacheConfiguration::class,
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
    ToolSanitizerConfiguration::class
)
class ArcReactorAutoConfiguration
