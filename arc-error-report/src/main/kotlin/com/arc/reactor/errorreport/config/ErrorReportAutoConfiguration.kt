package com.arc.reactor.errorreport.config

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.errorreport.handler.DefaultErrorReportHandler
import com.arc.reactor.errorreport.handler.ErrorReportHandler
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for the error report module.
 *
 * Activated when `arc.reactor.error-report.enabled=true`.
 * All beans use @ConditionalOnMissingBean -- override with custom implementations.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.error-report", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@EnableConfigurationProperties(ErrorReportProperties::class)
class ErrorReportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun errorReportHandler(
        agentExecutor: AgentExecutor,
        properties: ErrorReportProperties
    ): ErrorReportHandler = DefaultErrorReportHandler(
        agentExecutor = agentExecutor,
        properties = properties
    )
}
