package com.arc.reactor.autoconfigure

import com.arc.reactor.tracing.ArcReactorTracer
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class TracingConfigurationBeanNameTest {

    private val contextRunner = ApplicationContextRunner()
        .withPropertyValues(
            "arc.reactor.postgres.required=false",
            "arc.reactor.tracing.enabled=true",
            "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough"
        )
        .withConfiguration(AutoConfigurations.of(ArcReactorAutoConfiguration::class.java))

    @Test
    fun `should register arcReactorOtelTracer without colliding with existing otelTracer bean name`() {
        contextRunner
            .withUserConfiguration(
                ExistingOtelTracerBeanConfig::class.java,
                OpenTelemetryTestConfig::class.java
            )
            .run { context ->
                assertNull(context.startupFailure) {
                    "Context should start when an unrelated otelTracer bean name is already present"
                }
                val tracerBeans = context.getBeanNamesForType(ArcReactorTracer::class.java)
                assertArrayEquals(
                    arrayOf("arcReactorOtelTracer"),
                    tracerBeans,
                    "Arc Reactor tracer bean should use arcReactorOtelTracer name to avoid collisions"
                )
            }
    }

    @Configuration(proxyBeanMethods = false)
    private class ExistingOtelTracerBeanConfig {
        @Bean("otelTracer")
        fun existingBeanNamedOtelTracer(): String = "reserved-by-otel-autoconfigure"
    }

    @Configuration(proxyBeanMethods = false)
    private class OpenTelemetryTestConfig {
        @Bean
        fun openTelemetry(): OpenTelemetry = OpenTelemetry.noop()
    }
}
