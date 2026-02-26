package com.arc.reactor.errorreport

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.errorreport.config.ErrorReportAutoConfiguration
import com.arc.reactor.errorreport.handler.ErrorReportHandler
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ErrorReportAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ErrorReportAutoConfiguration::class.java))

    @Nested
    inner class ConditionalActivation {

        @Test
        fun `beans are NOT created when error-report is disabled`() {
            contextRunner
                .withPropertyValues("arc.reactor.error-report.enabled=false")
                .run { context ->
                    context.getBeansOfType(ErrorReportHandler::class.java).isEmpty()
                        .shouldBeTrue()
                }
        }

        @Test
        fun `beans are NOT created without enabled property`() {
            contextRunner.run { context ->
                context.getBeansOfType(ErrorReportHandler::class.java).isEmpty()
                    .shouldBeTrue()
            }
        }
    }

    @Nested
    inner class BeanCreation {

        @Test
        fun `ErrorReportHandler is created when enabled with AgentExecutor`() {
            contextRunner
                .withPropertyValues("arc.reactor.error-report.enabled=true")
                .withBean(AgentExecutor::class.java, { mockk(relaxed = true) })
                .run { context ->
                    context.getBean(ErrorReportHandler::class.java).shouldNotBeNull()
                }
        }

        @Test
        fun `ErrorReportHandler is NOT created without AgentExecutor`() {
            contextRunner
                .withPropertyValues("arc.reactor.error-report.enabled=true")
                .run { context ->
                    context.getBeansOfType(ErrorReportHandler::class.java).isEmpty()
                        .shouldBeTrue()
                }
        }
    }

    @Nested
    inner class CustomOverride {

        @Test
        fun `custom ErrorReportHandler bean takes precedence`() {
            val customHandler = mockk<ErrorReportHandler>()
            contextRunner
                .withPropertyValues("arc.reactor.error-report.enabled=true")
                .withBean(AgentExecutor::class.java, { mockk(relaxed = true) })
                .withBean(ErrorReportHandler::class.java, { customHandler })
                .run { context ->
                    (context.getBean(ErrorReportHandler::class.java) === customHandler)
                        .shouldBeTrue()
                }
        }
    }
}
