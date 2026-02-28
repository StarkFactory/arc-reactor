package com.arc.reactor.teams.config

import com.arc.reactor.scheduler.TeamsMessageSender
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for the Microsoft Teams integration module.
 *
 * Activated when `arc.reactor.teams.enabled=true`.
 * All beans use @ConditionalOnMissingBean — override with custom implementations.
 */
@Configuration
@ConditionalOnProperty(prefix = "arc.reactor.teams", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(TeamsProperties::class)
class TeamsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun teamsWebhookClient(properties: TeamsProperties): TeamsMessageSender =
        TeamsWebhookClient(properties)
}
