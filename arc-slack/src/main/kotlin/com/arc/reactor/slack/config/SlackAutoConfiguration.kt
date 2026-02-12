package com.arc.reactor.slack.config

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.slack.handler.DefaultSlackEventHandler
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.security.SlackSignatureVerifier
import com.arc.reactor.slack.security.SlackSignatureWebFilter
import com.arc.reactor.slack.service.SlackMessagingService
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
 * Auto-configuration for the Slack integration module.
 *
 * Activated when `arc.reactor.slack.enabled=true`.
 * All beans use @ConditionalOnMissingBean -- override with custom implementations.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.slack", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@EnableConfigurationProperties(SlackProperties::class)
class SlackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun slackSignatureVerifier(properties: SlackProperties): SlackSignatureVerifier =
        SlackSignatureVerifier(
            signingSecret = properties.signingSecret,
            timestampToleranceSeconds = properties.timestampToleranceSeconds
        )

    @Bean("slackSignatureWebFilter")
    @ConditionalOnMissingBean(name = ["slackSignatureWebFilter"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack", name = ["signature-verification-enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun slackSignatureWebFilter(
        verifier: SlackSignatureVerifier,
        objectMapper: ObjectProvider<ObjectMapper>
    ): WebFilter = SlackSignatureWebFilter(
        verifier,
        objectMapper.ifAvailable ?: ObjectMapper()
    )

    @Bean
    @ConditionalOnMissingBean
    fun slackMessagingService(properties: SlackProperties): SlackMessagingService =
        SlackMessagingService(botToken = properties.botToken)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun slackEventHandler(
        agentExecutor: AgentExecutor,
        messagingService: SlackMessagingService
    ): SlackEventHandler = DefaultSlackEventHandler(
        agentExecutor = agentExecutor,
        messagingService = messagingService
    )

}
