package com.arc.reactor.slack.config

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.slack.handler.DefaultSlackCommandHandler
import com.arc.reactor.slack.handler.DefaultSlackEventHandler
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.gateway.SlackSocketModeGateway
import com.arc.reactor.slack.metrics.MicrometerSlackMetricsRecorder
import com.arc.reactor.slack.metrics.NoOpSlackMetricsRecorder
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.processor.SlackCommandProcessor
import com.arc.reactor.slack.processor.SlackEventProcessor
import com.arc.reactor.slack.security.SlackSignatureVerifier
import com.arc.reactor.slack.security.SlackSignatureWebFilter
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
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
    fun slackMetricsRecorder(): SlackMetricsRecorder = NoOpSlackMetricsRecorder()

    @Bean
    @ConditionalOnClass(MeterRegistry::class)
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean
    fun micrometerSlackMetricsRecorder(registry: MeterRegistry): SlackMetricsRecorder =
        MicrometerSlackMetricsRecorder(registry)

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
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack", name = ["transport-mode"],
        havingValue = "events_api", matchIfMissing = true
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
    fun slackMessagingService(
        properties: SlackProperties,
        metricsRecorder: SlackMetricsRecorder
    ): SlackMessagingService =
        SlackMessagingService(
            botToken = properties.botToken,
            maxApiRetries = properties.apiMaxRetries,
            retryDefaultDelayMs = properties.apiRetryDefaultDelayMs,
            metricsRecorder = metricsRecorder
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun slackEventHandler(
        agentExecutor: AgentExecutor,
        messagingService: SlackMessagingService,
        agentProperties: ObjectProvider<AgentProperties>
    ): SlackEventHandler = DefaultSlackEventHandler(
        agentExecutor = agentExecutor,
        messagingService = messagingService,
        defaultProvider = agentProperties.ifAvailable?.llm?.defaultProvider ?: "configured backend model"
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun slackCommandHandler(
        agentExecutor: AgentExecutor,
        messagingService: SlackMessagingService,
        agentProperties: ObjectProvider<AgentProperties>
    ): SlackCommandHandler = DefaultSlackCommandHandler(
        agentExecutor = agentExecutor,
        messagingService = messagingService,
        defaultProvider = agentProperties.ifAvailable?.llm?.defaultProvider ?: "configured backend model"
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SlackEventHandler::class)
    fun slackEventProcessor(
        eventHandler: SlackEventHandler,
        messagingService: SlackMessagingService,
        metricsRecorder: SlackMetricsRecorder,
        properties: SlackProperties
    ): SlackEventProcessor = SlackEventProcessor(
        eventHandler = eventHandler,
        messagingService = messagingService,
        metricsRecorder = metricsRecorder,
        properties = properties
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SlackCommandHandler::class)
    fun slackCommandProcessor(
        commandHandler: SlackCommandHandler,
        messagingService: SlackMessagingService,
        metricsRecorder: SlackMetricsRecorder,
        properties: SlackProperties
    ): SlackCommandProcessor = SlackCommandProcessor(
        commandHandler = commandHandler,
        messagingService = messagingService,
        metricsRecorder = metricsRecorder,
        properties = properties
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(value = [SlackEventProcessor::class, SlackCommandProcessor::class])
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack",
        name = ["transport-mode"],
        havingValue = "socket_mode"
    )
    fun slackSocketModeGateway(
        properties: SlackProperties,
        objectMapper: ObjectMapper,
        commandProcessor: SlackCommandProcessor,
        eventProcessor: SlackEventProcessor,
        messagingService: SlackMessagingService,
        metricsRecorder: SlackMetricsRecorder
    ): SlackSocketModeGateway = SlackSocketModeGateway(
        properties = properties,
        objectMapper = objectMapper,
        commandProcessor = commandProcessor,
        eventProcessor = eventProcessor,
        messagingService = messagingService,
        metricsRecorder = metricsRecorder
    )

}
