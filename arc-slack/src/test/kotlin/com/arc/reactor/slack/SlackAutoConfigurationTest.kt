package com.arc.reactor.slack

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.scheduler.SlackMessageSender
import com.arc.reactor.slack.adapter.SlackMessageSenderAdapter
import com.arc.reactor.slack.config.SlackAutoConfiguration
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.metrics.MicrometerSlackMetricsRecorder
import com.arc.reactor.slack.metrics.NoOpSlackMetricsRecorder
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.security.SlackSignatureVerifier
import com.arc.reactor.slack.service.SlackMessagingService
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.mockk
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
        fun `beans are NOT created when slack은(는) disabled이다`() {
            contextRunner
                .withPropertyValues("arc.reactor.slack.enabled=false")
                .run { context ->
                    context.getBeansOfType(SlackSignatureVerifier::class.java).isEmpty()
                        .shouldBeTrue()
                }
        }

        @Test
        fun `beans은(는) NOT created without enabled property이다`() {
            contextRunner.run { context ->
                context.getBeansOfType(SlackSignatureVerifier::class.java).isEmpty()
                    .shouldBeTrue()
            }
        }
    }

    @Nested
    inner class BeanCreation {

        @Test
        fun `enabled일 때 SlackSignatureVerifier은(는) created이다`() {
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
        fun `enabled일 때 SlackMessagingService은(는) created이다`() {
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

        @Test
        fun `AgentExecutor bean exists일 때 SlackCommandHandler은(는) created이다`() {
            contextRunner
                .withBean(
                    AgentExecutor::class.java,
                    java.util.function.Supplier { mockk<AgentExecutor>(relaxed = true) }
                )
                .withPropertyValues(
                    "arc.reactor.slack.enabled=true",
                    "arc.reactor.slack.signing-secret=test-secret",
                    "arc.reactor.slack.bot-token=xoxb-test"
                )
                .run { context ->
                    context.getBean(SlackCommandHandler::class.java).shouldNotBeNull()
                }
        }

        @Test
        fun `MeterRegistry exists일 때 slack metrics recorder uses Micrometer implementation`() {
            contextRunner
                .withBean(
                    MeterRegistry::class.java,
                    java.util.function.Supplier { SimpleMeterRegistry() }
                )
                .withPropertyValues(
                    "arc.reactor.slack.enabled=true",
                    "arc.reactor.slack.signing-secret=test-secret",
                    "arc.reactor.slack.bot-token=xoxb-test"
                )
                .run { context ->
                    context.getBean(SlackMetricsRecorder::class.java)
                        .shouldBeInstanceOf<MicrometerSlackMetricsRecorder>()
                }
        }

        @Test
        fun `slack metrics recorder falls back to NoOp when MeterRegistry은(는) absent이다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.slack.enabled=true",
                    "arc.reactor.slack.signing-secret=test-secret",
                    "arc.reactor.slack.bot-token=xoxb-test"
                )
                .run { context ->
                    context.getBean(SlackMetricsRecorder::class.java)
                        .shouldBeInstanceOf<NoOpSlackMetricsRecorder>()
                }
        }
    }

    @Nested
    inner class SlackMessageSenderAdapterWiring {

        @Test
        fun `Slack is enabled일 때 SlackMessageSender adapter bean은(는) created이다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.slack.enabled=true",
                    "arc.reactor.slack.signing-secret=test-secret",
                    "arc.reactor.slack.bot-token=xoxb-test"
                )
                .run { context ->
                    context.getBean(SlackMessageSender::class.java)
                        .shouldBeInstanceOf<SlackMessageSenderAdapter>()
                }
        }

        @Test
        fun `Slack is disabled일 때 SlackMessageSender adapter은(는) NOT created이다`() {
            contextRunner
                .withPropertyValues("arc.reactor.slack.enabled=false")
                .run { context ->
                    context.getBeansOfType(SlackMessageSender::class.java).isEmpty()
                        .shouldBeTrue()
                }
        }

        @Test
        fun `커스텀 SlackMessageSender overrides adapter via ConditionalOnMissingBean`() {
            val customSender = SlackMessageSender { _, _ -> }
            contextRunner
                .withPropertyValues(
                    "arc.reactor.slack.enabled=true",
                    "arc.reactor.slack.signing-secret=test-secret",
                    "arc.reactor.slack.bot-token=xoxb-test"
                )
                .withBean(SlackMessageSender::class.java, { customSender })
                .run { context ->
                    val bean = context.getBean(SlackMessageSender::class.java)
                    (bean === customSender).shouldBeTrue()
                }
        }
    }

    @Nested
    inner class SignatureVerificationToggle {

        @Test
        fun `enabled일 때 WebFilter은(는) created by default이다`() {
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
        fun `signature verification disabled일 때 WebFilter은(는) NOT created이다`() {
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

        @Test
        fun `WebFilter은(는) NOT created in socket mode even if signature verification is enabled이다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.slack.enabled=true",
                    "arc.reactor.slack.transport-mode=socket_mode",
                    "arc.reactor.slack.signature-verification-enabled=true",
                    "arc.reactor.slack.signing-secret=test-secret",
                    "arc.reactor.slack.bot-token=xoxb-test"
                )
                .run { context ->
                    context.containsBean("slackSignatureWebFilter").shouldBeFalse()
                }
        }
    }
}
