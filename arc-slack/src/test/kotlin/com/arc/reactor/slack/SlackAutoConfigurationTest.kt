package com.arc.reactor.slack

import com.arc.reactor.slack.config.SlackAutoConfiguration
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.security.SlackSignatureVerifier
import com.arc.reactor.slack.service.SlackMessagingService
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class SlackAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SlackAutoConfiguration::class.java))

    @Nested
    inner class ConditionalActivation {

        @Test
        fun `beans are NOT created when slack is disabled`() {
            contextRunner
                .withPropertyValues("arc.reactor.slack.enabled=false")
                .run { context ->
                    context.getBeansOfType(SlackSignatureVerifier::class.java).isEmpty()
                        .shouldBeTrue()
                }
        }

        @Test
        fun `beans are NOT created without enabled property`() {
            contextRunner.run { context ->
                context.getBeansOfType(SlackSignatureVerifier::class.java).isEmpty()
                    .shouldBeTrue()
            }
        }
    }

    @Nested
    inner class BeanCreation {

        @Test
        fun `SlackSignatureVerifier is created when enabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.slack.enabled=true",
                    "arc.reactor.slack.signing-secret=test-secret",
                    "arc.reactor.slack.bot-token=xoxb-test"
                )
                .run { context ->
                    context.getBean(SlackSignatureVerifier::class.java).shouldNotBeNull()
                }
        }

        @Test
        fun `SlackMessagingService is created when enabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.slack.enabled=true",
                    "arc.reactor.slack.signing-secret=test-secret",
                    "arc.reactor.slack.bot-token=xoxb-test"
                )
                .run { context ->
                    context.getBean(SlackMessagingService::class.java).shouldNotBeNull()
                }
        }
    }

    @Nested
    inner class SignatureVerificationToggle {

        @Test
        fun `WebFilter is created by default when enabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.slack.enabled=true",
                    "arc.reactor.slack.signing-secret=test-secret",
                    "arc.reactor.slack.bot-token=xoxb-test"
                )
                .run { context ->
                    context.containsBean("slackSignatureWebFilter").shouldBeTrue()
                }
        }

        @Test
        fun `WebFilter is NOT created when signature verification disabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.slack.enabled=true",
                    "arc.reactor.slack.signing-secret=test-secret",
                    "arc.reactor.slack.bot-token=xoxb-test",
                    "arc.reactor.slack.signature-verification-enabled=false"
                )
                .run { context ->
                    context.containsBean("slackSignatureWebFilter").shouldBeFalse()
                }
        }
    }
}
