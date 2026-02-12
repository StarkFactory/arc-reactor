package com.arc.reactor.line

import com.arc.reactor.line.config.LineAutoConfiguration
import com.arc.reactor.line.security.LineSignatureVerifier
import com.arc.reactor.line.service.LineMessagingService
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LineAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LineAutoConfiguration::class.java))

    @Nested
    inner class ConditionalActivation {

        @Test
        fun `beans are NOT created when line is disabled`() {
            contextRunner
                .withPropertyValues("arc.reactor.line.enabled=false")
                .run { context ->
                    context.getBeansOfType(LineSignatureVerifier::class.java).isEmpty()
                        .shouldBeTrue()
                }
        }

        @Test
        fun `beans are NOT created without enabled property`() {
            contextRunner.run { context ->
                context.getBeansOfType(LineSignatureVerifier::class.java).isEmpty()
                    .shouldBeTrue()
            }
        }
    }

    @Nested
    inner class BeanCreation {

        @Test
        fun `LineSignatureVerifier is created when enabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.line.enabled=true",
                    "arc.reactor.line.channel-secret=test-secret",
                    "arc.reactor.line.channel-token=test-token"
                )
                .run { context ->
                    context.getBean(LineSignatureVerifier::class.java)
                        .shouldNotBeNull()
                }
        }

        @Test
        fun `LineMessagingService is created when enabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.line.enabled=true",
                    "arc.reactor.line.channel-secret=test-secret",
                    "arc.reactor.line.channel-token=test-token"
                )
                .run { context ->
                    context.getBean(LineMessagingService::class.java)
                        .shouldNotBeNull()
                }
        }
    }

    @Nested
    inner class SignatureVerificationToggle {

        @Test
        fun `WebFilter is created by default when enabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.line.enabled=true",
                    "arc.reactor.line.channel-secret=test-secret",
                    "arc.reactor.line.channel-token=test-token"
                )
                .run { context ->
                    context.containsBean("lineSignatureWebFilter")
                        .shouldBeTrue()
                }
        }

        @Test
        fun `WebFilter is NOT created when signature verification disabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.line.enabled=true",
                    "arc.reactor.line.channel-secret=test-secret",
                    "arc.reactor.line.channel-token=test-token",
                    "arc.reactor.line.signature-verification-enabled=false"
                )
                .run { context ->
                    context.containsBean("lineSignatureWebFilter")
                        .shouldBeFalse()
                }
        }
    }
}
