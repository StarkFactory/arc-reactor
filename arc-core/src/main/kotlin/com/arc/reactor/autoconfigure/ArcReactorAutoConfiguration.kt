package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.promptlab.autoconfigure.PromptLabConfiguration
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import

/**
 * Arc Reactor Auto Configuration
 *
 * Spring Boot auto-configuration entrypoint for Arc Reactor components.
 * Runs after Spring AI provider auto-configurations to ensure ChatModel beans are available.
 */
@AutoConfiguration(after = [GoogleGenAiChatAutoConfiguration::class, ChatClientAutoConfiguration::class])
@EnableConfigurationProperties(AgentProperties::class)
@Import(
    TracingConfiguration::class,
    ArcReactorCoreBeansConfiguration::class,
    ArcReactorHookAndMcpConfiguration::class,
    ArcReactorRuntimeConfiguration::class,
    ArcReactorExecutorConfiguration::class,
    JdbcMemoryStoreConfiguration::class,
    JdbcToolPolicyStoreConfiguration::class,
    JdbcRagIngestionPolicyStoreConfiguration::class,
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
    PromptCachingConfiguration::class
)
class ArcReactorAutoConfiguration
