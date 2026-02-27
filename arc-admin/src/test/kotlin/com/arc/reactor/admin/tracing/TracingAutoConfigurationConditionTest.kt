package com.arc.reactor.admin.tracing

import com.arc.reactor.admin.config.AdminProperties
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/**
 * Regression tests for the TracingAutoConfiguration startup bug.
 *
 * Previously, TracingAutoConfiguration lacked a @ConditionalOnProperty guard and
 * activated regardless of the arc.reactor.admin.enabled flag. This caused OTel
 * SDK beans to be registered in all deployments, producing startup failures when
 * OTel infrastructure was absent and admin was intentionally disabled.
 */
class TracingAutoConfigurationConditionTest {

    /**
     * Minimal configuration that satisfies @EnableConfigurationProperties(AdminProperties::class)
     * inside TracingAutoConfiguration without pulling in the full admin auto-configuration.
     */
    @Configuration
    @EnableConfigurationProperties(AdminProperties::class)
    class AdminPropertiesConfig

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TracingAutoConfiguration::class.java))
        .withUserConfiguration(AdminPropertiesConfig::class.java)

    @Nested
    inner class WhenAdminDisabled {

        @Test
        @Tag("regression")
        fun `should not activate TracingAutoConfiguration when arc-reactor-admin-enabled is false (default)`() {
            runner
                .withPropertyValues("arc.reactor.admin.enabled=false")
                .run { context ->
                    assertThat(context)
                        .doesNotHaveBean(SdkTracerProvider::class.java)
                        .withFailMessage(
                            "SdkTracerProvider must NOT be registered when arc.reactor.admin.enabled=false"
                        )
                    assertThat(context)
                        .doesNotHaveBean(OpenTelemetry::class.java)
                        .withFailMessage(
                            "OpenTelemetry must NOT be registered when arc.reactor.admin.enabled=false"
                        )
                }
        }

        @Test
        @Tag("regression")
        fun `should not activate TracingAutoConfiguration when arc-reactor-admin-enabled property is absent`() {
            runner
                .run { context ->
                    assertThat(context)
                        .doesNotHaveBean(SdkTracerProvider::class.java)
                        .withFailMessage(
                            "SdkTracerProvider must NOT be registered when arc.reactor.admin.enabled is not set"
                        )
                }
        }
    }

    @Nested
    inner class WhenAdminEnabled {

        @Test
        @Tag("regression")
        fun `should activate TracingAutoConfiguration when arc-reactor-admin-enabled is true`() {
            runner
                .withPropertyValues("arc.reactor.admin.enabled=true")
                .run { context ->
                    assertThat(context)
                        .hasSingleBean(SdkTracerProvider::class.java)
                        .withFailMessage(
                            "SdkTracerProvider must be registered when arc.reactor.admin.enabled=true"
                        )
                    assertThat(context)
                        .hasSingleBean(OpenTelemetry::class.java)
                        .withFailMessage(
                            "OpenTelemetry must be registered when arc.reactor.admin.enabled=true"
                        )
                }
        }
    }
}
