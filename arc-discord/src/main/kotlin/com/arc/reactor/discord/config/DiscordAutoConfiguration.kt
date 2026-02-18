package com.arc.reactor.discord.config

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.discord.handler.DefaultDiscordEventHandler
import com.arc.reactor.discord.handler.DiscordEventHandler
import com.arc.reactor.discord.listener.DiscordMessageListener
import com.arc.reactor.discord.service.DiscordMessagingService
import discord4j.core.DiscordClientBuilder
import discord4j.core.GatewayDiscordClient
import mu.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener

private val logger = KotlinLogging.logger {}

/**
 * Auto-configuration for the Discord integration module.
 *
 * Activated when `arc.reactor.discord.enabled=true`.
 * All beans use @ConditionalOnMissingBean -- override with custom implementations.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.discord", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@EnableConfigurationProperties(DiscordProperties::class)
class DiscordAutoConfiguration(
    private val listenerProvider: ObjectProvider<DiscordMessageListener>
) {

    @Bean
    @ConditionalOnMissingBean
    fun gatewayDiscordClient(properties: DiscordProperties): GatewayDiscordClient {
        logger.info { "Creating Discord gateway client" }
        return checkNotNull(
            DiscordClientBuilder.create(properties.token)
            .build()
            .login()
            .block()
        ) {
            "Discord login returned null GatewayDiscordClient. Verify token and gateway connectivity."
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun discordMessagingService(client: GatewayDiscordClient): DiscordMessagingService =
        DiscordMessagingService(client)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun discordEventHandler(
        agentExecutor: AgentExecutor,
        messagingService: DiscordMessagingService,
        client: GatewayDiscordClient
    ): DiscordEventHandler = DefaultDiscordEventHandler(
        agentExecutor = agentExecutor,
        messagingService = messagingService,
        selfId = client.selfId.asString()
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DiscordEventHandler::class)
    fun discordMessageListener(
        client: GatewayDiscordClient,
        handler: DiscordEventHandler,
        properties: DiscordProperties
    ): DiscordMessageListener = DiscordMessageListener(client, handler, properties)

    /**
     * Starts the Discord message listener after the application context is ready.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        listenerProvider.ifAvailable { listener ->
            listener.startListening()
        }
    }
}
