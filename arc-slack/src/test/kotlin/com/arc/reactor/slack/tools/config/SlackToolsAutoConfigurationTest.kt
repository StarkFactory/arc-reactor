package com.arc.reactor.slack.tools.config

import com.arc.reactor.slack.tools.client.SlackApiClient
import com.arc.reactor.slack.tools.observability.ToolObservabilityAspect
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.LocalToolFilter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class SlackToolsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SlackToolsAutoConfiguration::class.java))

    @Test
    fun `slack tools beans are not created when disabled`() {
        contextRunner
            .withPropertyValues("arc.reactor.slack.tools.enabled=false")
            .run { context ->
                assertTrue(context.getBeansOfType(SlackApiClient::class.java).isEmpty())
                assertTrue(context.getBeansOfType(LocalTool::class.java).isEmpty())
            }
    }

    @Test
    fun `slack tools beans are created when enabled`() {
        contextRunner
            .withPropertyValues(
                "arc.reactor.slack.tools.enabled=true",
                "arc.reactor.slack.tools.bot-token=xoxb-test"
            )
            .run { context ->
                assertEquals(1, context.getBeansOfType(SlackApiClient::class.java).size)
                assertEquals(11, context.getBeansOfType(LocalTool::class.java).size)
                assertEquals(1, context.getBeansOfType(LocalToolFilter::class.java).size)
            }
    }

    @Test
    fun `observability aspect is created when meter registry exists`() {
        contextRunner
            .withBean(MeterRegistry::class.java, java.util.function.Supplier { SimpleMeterRegistry() })
            .withPropertyValues(
                "arc.reactor.slack.tools.enabled=true",
                "arc.reactor.slack.tools.bot-token=xoxb-test"
            )
            .run { context ->
                assertEquals(1, context.getBeansOfType(ToolObservabilityAspect::class.java).size)
            }
    }
}
