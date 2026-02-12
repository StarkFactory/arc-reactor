package com.arc.reactor.line.config

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.line.handler.DefaultLineEventHandler
import com.arc.reactor.line.handler.LineEventHandler
import com.arc.reactor.line.security.LineSignatureVerifier
import com.arc.reactor.line.security.LineSignatureWebFilter
import com.arc.reactor.line.service.LineMessagingService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.WebFilter

/**
 * Auto-configuration for the LINE integration module.
 *
 * Activated when `arc.reactor.line.enabled=true`.
 * All beans use @ConditionalOnMissingBean -- override with custom implementations.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.line", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@EnableConfigurationProperties(LineProperties::class)
class LineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun lineSignatureVerifier(properties: LineProperties): LineSignatureVerifier =
        LineSignatureVerifier(channelSecret = properties.channelSecret)

    @Bean("lineSignatureWebFilter")
    @ConditionalOnMissingBean(name = ["lineSignatureWebFilter"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.line", name = ["signature-verification-enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun lineSignatureWebFilter(
        verifier: LineSignatureVerifier,
        objectMapper: ObjectProvider<ObjectMapper>
    ): WebFilter = LineSignatureWebFilter(
        verifier,
        objectMapper.ifAvailable ?: ObjectMapper()
    )

    @Bean
    @ConditionalOnMissingBean
    fun lineMessagingService(properties: LineProperties): LineMessagingService =
        LineMessagingService(channelToken = properties.channelToken)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun lineEventHandler(
        agentExecutor: AgentExecutor,
        messagingService: LineMessagingService
    ): LineEventHandler = DefaultLineEventHandler(
        agentExecutor = agentExecutor,
        messagingService = messagingService
    )

}
