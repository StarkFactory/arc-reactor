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

/**
 * [SlackToolsAutoConfiguration]의 자동 구성 테스트.
 *
 * 활성화/비활성화 속성에 따른 빈 등록 여부, MeterRegistry 존재 시 관측성 빈 생성,
 * 캔버스 기능 활성화 시 추가 도구 등록 등을 검증한다.
 */
class SlackToolsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SlackToolsAutoConfiguration::class.java))

    @Test
    fun `disabled일 때 slack tools beans are not created`() {
        contextRunner
            .withPropertyValues("arc.reactor.slack.tools.enabled=false")
            .run { context ->
                assertTrue(context.getBeansOfType(SlackApiClient::class.java).isEmpty())
                assertTrue(context.getBeansOfType(LocalTool::class.java).isEmpty())
            }
    }

    @Test
    fun `enabled일 때 slack tools beans are created`() {
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
    fun `meter registry exists일 때 observability aspect은(는) created이다`() {
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

    @Test
    fun `canvas tools are created when canvas feature은(는) enabled이다`() {
        contextRunner
            .withPropertyValues(
                "arc.reactor.slack.tools.enabled=true",
                "arc.reactor.slack.tools.bot-token=xoxb-test",
                "arc.reactor.slack.tools.canvas.enabled=true"
            )
            .run { context ->
                assertEquals(13, context.getBeansOfType(LocalTool::class.java).size)
            }
    }
}
