package com.arc.reactor.admin.tracing

import com.arc.reactor.admin.config.AdminProperties
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.samplers.Sampler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/**
 * TracingAutoConfiguration 시작 버그에 대한 회귀 테스트.
 *
 * 이전에는 TracingAutoConfiguration에 @ConditionalOnProperty 가드가 없어서
 * arc.reactor.admin.enabled 플래그와 무관하게 활성화되었습니다.
 * 이로 인해 OTel SDK 빈이 모든 배포에 등록되어, OTel 인프라가 없고
 * admin이 의도적으로 비활성화된 환경에서 시작 실패가 발생했습니다.
 */
class TracingAutoConfigurationConditionTest {

    /**
     * 전체 admin 자동 설정을 끌어오지 않으면서 TracingAutoConfiguration 내부의
     * @EnableConfigurationProperties(AdminProperties::class)를 충족하는 최소 설정.
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
        fun `arc-reactor-admin-enabled is false (default)일 때 not activate TracingAutoConfiguration해야 한다`() {
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
        fun `arc-reactor-admin-enabled property is absent일 때 not activate TracingAutoConfiguration해야 한다`() {
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
        fun `arc-reactor-admin-enabled is true일 때 activate TracingAutoConfiguration해야 한다`() {
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

        @Test
        fun `use AlwaysOnSampler by default해야 한다`() {
            runner
                .withPropertyValues("arc.reactor.admin.enabled=true")
                .run { context ->
                    val provider = context.getBean(SdkTracerProvider::class.java)
                    assertThat(provider.sampler.description)
                        .`as`("Default sampler should be AlwaysOnSampler")
                        .isEqualTo(Sampler.alwaysOn().description)
                }
        }

        @Test
        fun `sampling-rate is below 1일 때 use TraceIdRatioBased sampler해야 한다`() {
            runner
                .withPropertyValues(
                    "arc.reactor.admin.enabled=true",
                    "arc.reactor.admin.tracing.sampling-rate=0.1"
                )
                .run { context ->
                    val provider = context.getBean(SdkTracerProvider::class.java)
                    assertThat(provider.sampler.description)
                        .`as`("Sampler should be ratio-based when sampling-rate < 1.0")
                        .isEqualTo(Sampler.traceIdRatioBased(0.1).description)
                }
        }
    }
}
