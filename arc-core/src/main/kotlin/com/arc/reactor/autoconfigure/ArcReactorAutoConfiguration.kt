package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import

/**
 * Arc Reactor Auto Configuration
 *
 * Spring Boot auto-configuration entrypoint for Arc Reactor components.
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProperties::class)
@Import(
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
    SchedulerConfiguration::class
)
class ArcReactorAutoConfiguration
