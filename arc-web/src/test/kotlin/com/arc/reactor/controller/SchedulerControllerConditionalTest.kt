package com.arc.reactor.controller

import com.arc.reactor.scheduler.DynamicSchedulerService
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class SchedulerControllerConditionalTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
        .withUserConfiguration(
            SchedulerController::class.java,
            SchedulerControllerSupportConfig::class.java
        )

    @Test
    fun `controller is registered when scheduler is enabled`() {
        contextRunner
            .withPropertyValues("arc.reactor.scheduler.enabled=true")
            .run { context ->
                context.containsBean("schedulerController").shouldBeTrue()
            }
    }

    @Test
    fun `controller is not registered when scheduler is disabled`() {
        contextRunner
            .withPropertyValues("arc.reactor.scheduler.enabled=false")
            .run { context ->
                context.containsBean("schedulerController").shouldBeFalse()
            }
    }

    @Configuration
    class SchedulerControllerSupportConfig {

        @Bean
        fun dynamicSchedulerService(): DynamicSchedulerService = mockk(relaxed = true)
    }
}
