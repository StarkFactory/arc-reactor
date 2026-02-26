package com.arc.reactor.discord

import com.arc.reactor.discord.config.DiscordAutoConfiguration
import com.arc.reactor.discord.config.DiscordProperties
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DiscordAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DiscordAutoConfiguration::class.java))

    @Nested
    inner class ConditionalActivation {

        @Test
        fun `beans are NOT created when discord is disabled`() {
            contextRunner
                .withPropertyValues("arc.reactor.discord.enabled=false")
                .run { context ->
                    context.getBeansOfType(DiscordProperties::class.java).isEmpty()
                        .shouldBeTrue()
                }
        }

        @Test
        fun `beans are NOT created without enabled property`() {
            contextRunner.run { context ->
                context.getBeansOfType(DiscordProperties::class.java).isEmpty()
                    .shouldBeTrue()
            }
        }

        @Test
        fun `configuration is not active when enabled is missing`() {
            contextRunner.run { context ->
                context.containsBean("gatewayDiscordClient").shouldBeFalse()
                context.containsBean("discordMessagingService").shouldBeFalse()
            }
        }
    }
}
