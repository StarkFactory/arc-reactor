package com.arc.reactor.teams

import com.arc.reactor.scheduler.TeamsMessageSender
import com.arc.reactor.teams.config.TeamsAutoConfiguration
import com.arc.reactor.teams.config.TeamsWebhookClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class TeamsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TeamsAutoConfiguration::class.java))

    @Nested
    inner class ConditionalActivation {

        @Test
        fun `no bean created when teams is disabled`() {
            contextRunner
                .withPropertyValues("arc.reactor.teams.enabled=false")
                .run { context ->
                    assertFalse(
                        context.containsBean("teamsWebhookClient"),
                        "TeamsWebhookClient bean must NOT be created when teams is disabled"
                    )
                }
        }

        @Test
        fun `no bean created when property is absent`() {
            contextRunner.run { context ->
                assertTrue(
                    context.getBeansOfType(TeamsMessageSender::class.java).isEmpty(),
                    "TeamsMessageSender bean must NOT exist when arc.reactor.teams.enabled is not set"
                )
            }
        }

        @Test
        fun `TeamsWebhookClient bean created when enabled is true`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.teams.enabled=true",
                    "arc.reactor.teams.default-webhook-url=https://example.webhook.office.com/webhookb2/test"
                )
                .run { context ->
                    assertNotNull(
                        context.getBean(TeamsMessageSender::class.java),
                        "TeamsMessageSender bean must be created when arc.reactor.teams.enabled=true"
                    )
                }
        }

        @Test
        fun `created bean is a TeamsWebhookClient instance`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.teams.enabled=true",
                    "arc.reactor.teams.default-webhook-url=https://example.webhook.office.com/webhookb2/test"
                )
                .run { context ->
                    val sender = context.getBean(TeamsMessageSender::class.java)
                    assertTrue(
                        sender is TeamsWebhookClient,
                        "Default TeamsMessageSender implementation must be TeamsWebhookClient"
                    )
                }
        }
    }

    @Nested
    inner class ConditionalOnMissingBean {

        @Test
        fun `custom TeamsMessageSender is not overridden by auto-configuration`() {
            val customSender = TeamsMessageSender { _, _ -> }
            contextRunner
                .withBean(TeamsMessageSender::class.java, java.util.function.Supplier { customSender })
                .withPropertyValues(
                    "arc.reactor.teams.enabled=true",
                    "arc.reactor.teams.default-webhook-url=https://example.webhook.office.com/webhookb2/test"
                )
                .run { context ->
                    val resolved = context.getBean(TeamsMessageSender::class.java)
                    assertTrue(
                        resolved === customSender,
                        "Custom TeamsMessageSender bean must take precedence over auto-configured one"
                    )
                }
        }
    }
}
